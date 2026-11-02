package com.sicost.financeentries;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.security.*;
import com.sicost.entries.FinanceEntryMapper;
import com.sicost.uploadsops.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
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

class FinanceEntriesUploadPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    @TempDir Path directory;
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    FinanceEntriesUploadService service;
    JdbcTemplate jdbc;
    long original,child;
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
        factory.setMapperLocations(new ClassPathResource("mappers/financeentries/FinanceEntriesUploadMapper.xml"),
            new ClassPathResource("mappers/entries/FinanceEntryMapper.xml"),new ClassPathResource("mappers/uploads/UploadMapper.xml"));
        factory.afterPropertiesSet();var template=new SqlSessionTemplate(factory.getObject());
        var uploads=mock(UploadService.class);
        when(uploads.store(any(),anyString())).thenAnswer(i->{
            var file=i.getArgument(0,org.springframework.web.multipart.MultipartFile.class);
            Path target=directory.resolve(UUID.randomUUID()+".xlsx");Files.write(target,file.getBytes());return target;
        });
        context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("upload",Map.of("sicost.upload-directory",directory.toString())));
        context.register(Transactions.class,FinanceEntriesUploadService.class,FinanceEntriesUploadForm.class,
            UploadWorkbookWriter.class,CurrentUser.class,PermissionService.class);
        context.registerBean(FinanceEntriesUploadMapper.class,()->template.getMapper(FinanceEntriesUploadMapper.class));
        context.registerBean(FinanceEntryMapper.class,()->template.getMapper(FinanceEntryMapper.class));
        context.registerBean(UploadMapper.class,()->template.getMapper(UploadMapper.class));
        context.registerBean(UploadService.class,()->uploads);
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());context.refresh();
        service=context.getBean(FinanceEntriesUploadService.class);jdbc=new JdbcTemplate(source);
        original=jdbc.queryForObject("INSERT INTO fin.entry(entry_ym,fin_se_cd,item_nm,entry_amt) VALUES('2026-10','SGA','수기 원본',100) RETURNING entry_id",Long.class);
        child=jdbc.queryForObject("INSERT INTO fin.entry(entry_ym,fin_se_cd,item_nm,entry_amt,copy_entry_id) VALUES('2026-11','SGA','복제 참조',100,?) RETURNING entry_id",Long.class,original);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    long validate()throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("재무 항목");var title=sheet.createRow(3);
            for(int c=0;c<FinanceEntriesUploadForm.HEADERS.size();c++)title.createCell(c).setCellValue(FinanceEntriesUploadForm.HEADERS.get(c));
            var row=sheet.createRow(4);String[] values={"2026-10","판관비","업로드 항목","300","0"};
            for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);book.write(out);
            var result=service.validate(new MockMultipartFile("file","재무.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray()));
            assertThat(result.error_count()).isZero();return Long.parseLong(result.upload_id());
        }
    }
    String snapshot() {
        return jdbc.queryForObject("SELECT json_build_object('entries',(SELECT json_agg(e ORDER BY entry_id) FROM fin.entry e),'reviews',(SELECT json_agg(u ORDER BY upld_id) FROM sys.upld u),'issues',(SELECT json_agg(e ORDER BY upld_err_id) FROM sys.upld_err e),'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),'access',(SELECT json_agg(a ORDER BY acs_hist_id) FROM sys.acs_hist a))::text",String.class);
    }
    @Test void replacementPreservesSelfCopyReferencesAndOtherMonthsAndAuditsOldAndNew()throws Exception {
        long id=validate();var result=service.apply(id,true);assertThat(result.applied_count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_yn FROM fin.entry WHERE entry_id=?",String.class,original)).isEqualTo("Y");
        assertThat(jdbc.queryForObject("SELECT copy_entry_id FROM fin.entry WHERE entry_id=?",Long.class,child)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fin.entry WHERE entry_ym='2026-10' AND deleted_yn='N'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT upld_id FROM fin.entry WHERE item_nm='업로드 항목'",Long.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT obj_ym FROM sys.upld WHERE upld_id=?",String.class,id)).isEqualTo("2026-10");
        assertThat(jdbc.queryForObject("SELECT bfr_val::jsonb->>'item_nm' FROM sys.data_chg_hist WHERE tbl_nm='fin.entry' AND data_chg_ty_cd='DELETE'",String.class)).isEqualTo("수기 원본");
        assertThat(jdbc.queryForObject("SELECT aft_val::jsonb->>'entry_amt' FROM sys.data_chg_hist WHERE tbl_nm='fin.entry' AND data_chg_ty_cd='INSERT'",String.class)).isEqualTo("300");
        assertThat(jdbc.queryForObject("SELECT scrn_id FROM sys.acs_hist WHERE acs_ty_cd='UPLOAD' ORDER BY acs_hist_id DESC LIMIT 1",String.class)).isEqualTo("UI-10");
        String after=snapshot();service.apply(id,true);assertThat(snapshot()).isEqualTo(after);
    }
    @Test void previewDoesNotPersistAnyChanges()throws Exception {
        long id=validate();String before=snapshot();assertThat(service.apply(id,false).can_apply()).isTrue();assertThat(snapshot()).isEqualTo(before);
    }
    @Test void auditFailureRollsBackSoftDeletionNewRowsAndUpload()throws Exception {
        long id=validate();String before=snapshot();jdbc.execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.apply(id,true)).isInstanceOf(RuntimeException.class);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void accessFailureRollsBackAfterAllRowsWereWritten()throws Exception {
        long id=validate();String before=snapshot();jdbc.execute("ALTER TABLE sys.acs_hist ADD CONSTRAINT reject_access CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.apply(id,true)).isInstanceOf(RuntimeException.class);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void lateCloseRollsBackWithoutMutatingOriginal()throws Exception {
        long id=validate();jdbc.update("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");String before=snapshot();
        assertThatThrownBy(()->service.apply(id,true)).isInstanceOf(com.sicost.common.api.ApiException.class);assertThat(snapshot()).isEqualTo(before);
    }
}
