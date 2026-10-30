package com.sicost.priorprofit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.uploadsops.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PriorProfitUploadPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    @TempDir Path directory;
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PriorProfitUploadService service;
    JdbcTemplate jdbc;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {com.sicost.support.TestDatabaseDdl.create(statement,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/priorprofit/PriorProfitUploadMapper.xml"),new ClassPathResource("mappers/uploads/UploadMapper.xml"));
        factory.afterPropertiesSet();var template=new SqlSessionTemplate(factory.getObject());
        var uploads=mock(UploadService.class);
        when(uploads.store(any(),anyString())).thenAnswer(i->{
            var file=i.getArgument(0,org.springframework.web.multipart.MultipartFile.class);
            Path target=directory.resolve(UUID.randomUUID()+".xlsx");Files.write(target,file.getBytes());return target;
        });
        context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("upload",Map.of("sicost.upload-directory",directory.toString())));
        context.register(Transactions.class,PriorProfitUploadService.class,PriorProfitUploadForm.class,UploadWorkbookWriter.class,CurrentUser.class,PermissionService.class);
        context.registerBean(PriorProfitUploadMapper.class,()->template.getMapper(PriorProfitUploadMapper.class));
        context.registerBean(UploadMapper.class,()->template.getMapper(UploadMapper.class));context.registerBean(UploadService.class,()->uploads);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());context.refresh();
        service=context.getBean(PriorProfitUploadService.class);jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO fin.mth_pl(pl_ym,pl_src_cd,sls_amt) VALUES('2025-01','IMPORT',99),('2024-12','CLOSE',777)");
        jdbc.update("INSERT INTO fin.mth_pl_dtl(pl_ym,dim_ty_cd,dim_val,cst_amt) VALUES('2025-01','SGA_ITEM','RENT',9),('2024-12','SGA_ITEM','RENT',777)");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if(context!=null) context.close();if(source==null) return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {com.sicost.support.TestDatabaseDdl.drop(statement,database);}
    }
    long review() throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("月別");var title=sheet.createRow(3);
            for(int c=0;c<PriorProfitUploadForm.HEADERS.size();c++) title.createCell(c).setCellValue(PriorProfitUploadForm.HEADERS.get(c));
            for(int m=1;m<=12;m++) {
                var row=sheet.createRow(m+3);row.createCell(0).setCellValue(String.format("2025-%02d",m));
                for(int c=1;c<=11;c++) row.createCell(c).setCellValue(c==1?1000:c==5?100:c==8?-10:0);
            }
            var detail=book.createSheet("판관비 상세");var heading=detail.createRow(3);
            for(int c=0;c<3;c++) heading.createCell(c).setCellValue(PriorProfitUploadForm.DETAIL_HEADERS.get(c));
            String code=jdbc.queryForObject("SELECT dtl_cd FROM cmn.cd_dtl WHERE cd_grp_id='SGA_ITEM' AND use_yn='Y' ORDER BY sort_seq LIMIT 1",String.class);
            for(int m=1;m<=12;m++) {var row=detail.createRow(m+3);row.createCell(0).setCellValue(String.format("2025-%02d",m));row.createCell(1).setCellValue(code);row.createCell(2).setCellValue(100);}
            book.write(out);var result=service.validate(new MockMultipartFile("file","손익.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray()));
            assertThat(result.error_count()).isZero();return Long.parseLong(result.upload_id());
        }
    }
    String snapshot() {
        return jdbc.queryForObject("SELECT json_build_object('profits',(SELECT json_agg(p ORDER BY pl_ym) FROM fin.mth_pl p),'details',(SELECT json_agg(d ORDER BY pl_ym,dim_ty_cd,dim_val) FROM fin.mth_pl_dtl d),'reviews',(SELECT json_agg(u ORDER BY upld_id) FROM sys.upld u),'issues',(SELECT json_agg(e ORDER BY upld_err_id) FROM sys.upld_err e),'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),'access',(SELECT json_agg(a ORDER BY acs_hist_id) FROM sys.acs_hist a))::text",String.class);
    }
    @Test void annualReplacementUsesExistingCodesKeepsOtherYearsAndAuditsOldAndNew() throws Exception {
        long id=review();assertThat(service.apply(id,true).applied_count()).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fin.mth_pl WHERE pl_src_cd='IMPORT' AND substring(pl_ym,1,4)='2025'",Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT bsn_prft_amt FROM fin.mth_pl WHERE pl_ym='2025-01'",Long.class)).isEqualTo(890);
        assertThat(jdbc.queryForObject("SELECT sls_amt FROM fin.mth_pl WHERE pl_ym='2024-12'",Long.class)).isEqualTo(777);
        assertThat(jdbc.queryForObject("SELECT cst_amt FROM fin.mth_pl_dtl WHERE pl_ym='2024-12'",Long.class)).isEqualTo(777);
        assertThat(jdbc.queryForObject("SELECT bfr_val::jsonb->>'revenue' FROM sys.data_chg_hist WHERE tbl_nm='fin.mth_pl' AND data_chg_ty_cd='DELETE'",String.class)).isEqualTo("99");
        assertThat(jdbc.queryForObject("SELECT upld_stts_cd FROM sys.upld WHERE upld_id=?",String.class,id)).isEqualTo("APPLIED");
        String before=snapshot();service.apply(id,true);assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"sys.data_chg_hist","sys.acs_hist","fin.mth_pl_dtl"})
    void failureRollsBackDeletedAndInsertedRowsAndUploadAuditAccessTogether(String table) throws Exception {
        long id=review();String before=snapshot();jdbc.execute("ALTER TABLE "+table+" ADD CONSTRAINT reject_write CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.apply(id,true)).isInstanceOf(RuntimeException.class);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void previewHasNoPersistentSideEffects() throws Exception {
        long id=review();String before=snapshot();assertThat(service.apply(id,false).can_apply()).isTrue();assertThat(snapshot()).isEqualTo(before);
    }
    @Test void closeAddedAfterValidationBlocksWholeYearWithoutMutation() throws Exception {
        long id=review();jdbc.update("INSERT INTO fin.mth_pl(pl_ym,pl_src_cd,sls_amt) VALUES('2025-12','CLOSE',777)");String before=snapshot();
        assertThatThrownBy(()->service.apply(id,true)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));assertThat(snapshot()).isEqualTo(before);
    }
}
