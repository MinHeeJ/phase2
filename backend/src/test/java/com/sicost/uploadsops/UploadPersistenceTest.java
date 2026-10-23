package com.sicost.uploadsops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class UploadPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    UploadService service;
    UploadMapper mapper;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/uploads/UploadMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(UploadMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,UploadService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(UploadMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        Path directory=Files.createTempDirectory("upload-persistence-");context.registerBean(UploadStorage.class,()->new UploadStorage(directory.toString()));context.refresh();service=context.getBean(UploadService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    List<Long> counts()throws Exception {List<Long> counts=new ArrayList<>();for(String table:List.of("sys.upld","mst.ptnr","sys.upld_err","sys.data_chg_hist","sys.acs_hist"))counts.add(scalar("SELECT count(*) FROM "+table));return counts;}
    MockMultipartFile file(int count,boolean warning,boolean invalid)throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {var sheet=book.createSheet("입력");var h=sheet.createRow(3);for(int c=0;c<12;c++)h.createCell(c).setCellValue(UploadForm.TITLES[c]);
            for(int r=0;r<count;r++){var row=sheet.createRow(r+4);row.createCell(0).setCellValue("고객사");row.createCell(1).setCellValue("거래처"+r);row.createCell(2).setCellValue(invalid?"bad":String.format("%010d",1234567890L+r));row.createCell(7).setCellValue("tax@company.co.kr");if(warning&&r<3)row.createCell(8).setCellValue("무시할 은행");}book.write(out);return new MockMultipartFile("file","거래처.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());}
    }
    @Test void appliesFiveRowsWarningsAuditAndSequenceCodesAtomically()throws Exception {
        var before=counts();var result=service.validate("B.7",file(5,true,false));assertThat(counts()).isEqualTo(before);assertThat(result.warningRows()).isEqualTo(3);
        var applied=service.apply(result.token());assertThat(applied.appliedRows()).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE upld_id="+applied.upldId()+" AND use_yn='Y'")).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM sys.upld_err WHERE upld_id="+applied.upldId()+" AND err_svrt_cd='WARN'")).isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='INSERT' AND rsn_desc='엑셀 업로드 #"+applied.upldId()+"' AND chg_user_id IS NULL")).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='UPLOAD' AND scrn_id='UI-15' AND user_id IS NULL")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE ptnr_no='C-' || lpad(ptnr_id::text,3,'0')")).isEqualTo(5);
        assertThat(mapper.original(Long.valueOf(applied.upldId())).fileName()).isEqualTo("거래처.xlsx");assertThat(mapper.issues(Long.valueOf(applied.upldId()))).hasSize(3);
    }
    @Test void secondBusinessFailureRollsBackAllFiveLedgers()throws Exception {
        var result=service.validate("B.7",file(2,true,false));var before=counts();
        sql("ALTER TABLE mst.ptnr ADD CONSTRAINT reject_second CHECK (ptnr_nm <> '거래처1') NOT VALID");
        assertThatThrownBy(()->service.apply(result.token())).isInstanceOf(RuntimeException.class);assertThat(counts()).isEqualTo(before);
        sql("ALTER TABLE mst.ptnr DROP CONSTRAINT reject_second");assertThat(service.apply(result.token()).appliedRows()).isEqualTo(2);
    }
    @Test void concurrentSeparateTokensCommitOneAndRollbackLosingTransaction()throws Exception {
        var first=service.validate("B.7",file(2,true,false));var second=service.validate("B.7",file(2,true,false));var pool=Executors.newFixedThreadPool(2);var start=new CyclicBarrier(2);
        try {
            Callable<String> a=()->{start.await();try{service.apply(first.token());return "APPLIED";}catch(ApiException ex){return ex.code();}};
            Callable<String> b=()->{start.await();try{service.apply(second.token());return "APPLIED";}catch(ApiException ex){return ex.code();}};
            Future<String> fa=pool.submit(a),fb=pool.submit(b);assertThat(List.of(fa.get(20,TimeUnit.SECONDS),fb.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("APPLIED","STALE_VALIDATION");
            assertThat(scalar("SELECT count(*) FROM sys.upld")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(2);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.ptnr'")).isEqualTo(2);assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='UPLOAD'")).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void rejectedAndRecheckedRecordsAppendWithoutChangingPartners()throws Exception {
        var result=service.validate("B.7",file(1,true,true));assertThat(result.errorRows()).isEqualTo(1);assertThat(mapper.issues(Long.valueOf(result.upldId()))).hasSize(2);long before=scalar("SELECT count(*) FROM mst.ptnr");var again=service.recheck(result.upldId());assertThat(again.upldId()).isNotEqualTo(result.upldId());assertThat(scalar("SELECT count(*) FROM sys.upld")).isEqualTo(2);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(before);assertThat(service.errorFile(result.upldId()).bytes()).isNotEmpty();
    }
    @Test void templateListsOnlyActiveCodesAndAccessRecordsNullCurrentUser()throws Exception {
        assertThat(mapper.codes()).anyMatch(c->c.group().equals("BANK")&&c.code().equals("KB"));service.template("B.7");assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND obj_desc='거래처_업로드양식.xlsx' AND user_id IS NULL")).isEqualTo(1);
    }
}
