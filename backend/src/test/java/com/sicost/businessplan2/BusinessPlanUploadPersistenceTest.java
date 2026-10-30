package com.sicost.businessplan2;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class BusinessPlanUploadPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    @TempDir Path directory;
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BusinessPlanUploadService service;
    JdbcTemplate jdbc;
    long part;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/businessplan2/BusinessPlanUploadMapper.xml"),new ClassPathResource("mappers/uploads/UploadMapper.xml"));
        factory.afterPropertiesSet();var template=new SqlSessionTemplate(factory.getObject());
        var uploads=mock(UploadService.class);
        when(uploads.store(any(),anyString())).thenAnswer(i->{
            var file=i.getArgument(0,org.springframework.web.multipart.MultipartFile.class);
            Path target=directory.resolve(UUID.randomUUID()+".xlsx");Files.write(target,file.getBytes());return target;
        });
        context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("upload",Map.of("sicost.upload-directory",directory.toString())));
        context.register(Transactions.class,BusinessPlanUploadService.class,BusinessPlanUploadForm.class,
            UploadWorkbookWriter.class,CurrentUser.class,PermissionService.class);
        context.registerBean(BusinessPlanUploadMapper.class,()->template.getMapper(BusinessPlanUploadMapper.class));
        context.registerBean(UploadMapper.class,()->template.getMapper(UploadMapper.class));
        context.registerBean(UploadService.class,()->uploads);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());context.refresh();
        service=context.getBean(BusinessPlanUploadService.class);jdbc=new JdbcTemplate(source);
        part=jdbc.queryForObject("SELECT bsn_part_id FROM mst.bsn_part WHERE use_yn='Y' ORDER BY bsn_part_id LIMIT 1",Long.class);
        jdbc.update("INSERT INTO fin.biz_plan(plan_yr,plan_ym,bsn_part_id,trgt_sls_amt,trgt_bsn_prft_amt,trgt_utlz_rt) VALUES('2027','2027-01',?,100,10,50),('2027','2027-02',?,200,20,60)",part,part);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null) context.close();if(source==null) return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    String validate(String... lines)throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("사업계획");var title=sheet.createRow(3);
            for(int c=0;c<BusinessPlanUploadForm.HEADERS.size();c++) title.createCell(c).setCellValue(BusinessPlanUploadForm.HEADERS.get(c));
            for(int n=0;n<lines.length;n++) {
                var row=sheet.createRow(n+4);String[] values=lines[n].replace("PART",Long.toString(part)).split(",");
                for(int c=0;c<values.length;c++) row.createCell(c).setCellValue(values[c]);
            }
            book.write(out);
            return service.validate(new MockMultipartFile("file","계획.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray())).upload_id();
        }
    }
    String snapshot() {
        return jdbc.queryForObject("SELECT json_build_object('plans',(SELECT json_agg(p ORDER BY biz_plan_id) FROM fin.biz_plan p),'reviews',(SELECT json_agg(u ORDER BY upld_id) FROM sys.upld u),'issues',(SELECT json_agg(e ORDER BY upld_err_id) FROM sys.upld_err e),'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),'access',(SELECT json_agg(a ORDER BY acs_hist_id) FROM sys.acs_hist a))::text",String.class);
    }
    @Test void successfulReplacementKeepsClosedAndHeaderYearMatchesMonthAndAuditsOldAndNew()throws Exception {
        jdbc.update("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2027-01','CLOSED')");
        String closed=jdbc.queryForObject("SELECT row_to_json(p)::text FROM fin.biz_plan p WHERE plan_ym='2027-01'",String.class);
        String id=validate("2027,1,PART,100,10,50","2027,3,PART,300,-30,70");
        assertThat(service.apply(Long.parseLong(id),true).applied_count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT row_to_json(p)::text FROM fin.biz_plan p WHERE plan_ym='2027-01'",String.class)).isEqualTo(closed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fin.biz_plan WHERE plan_ym='2027-02'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fin.biz_plan WHERE plan_yr=substring(plan_ym,1,4)",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT bfr_val::jsonb->>'revenue' FROM sys.data_chg_hist WHERE tbl_nm='fin.biz_plan' AND data_chg_ty_cd='DELETE'",String.class)).isEqualTo("200");
        assertThat(jdbc.queryForObject("SELECT aft_val::jsonb->>'profit' FROM sys.data_chg_hist WHERE tbl_nm='fin.biz_plan' AND data_chg_ty_cd='INSERT'",String.class)).isEqualTo("-30");
    }
    @Test void auditFailureRollsBackReplacementUploadAndAccessTogether()throws Exception {
        String id=validate("2027,3,PART,300,30,70");String before=snapshot();
        jdbc.execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.apply(Long.parseLong(id),true)).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void accessFailureRollsBackEverythingAfterRowsWereInserted()throws Exception {
        String id=validate("2027,3,PART,300,30,70");String before=snapshot();
        jdbc.execute("ALTER TABLE sys.acs_hist ADD CONSTRAINT reject_access CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.apply(Long.parseLong(id),true)).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void previewDoesNotChangeAnyPersistedRows()throws Exception {
        String id=validate("2027,3,PART,300,30,70");String before=snapshot();
        assertThat(service.apply(Long.parseLong(id),false).can_apply()).isTrue();assertThat(snapshot()).isEqualTo(before);
    }
}
