package com.sicost.uploadsops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.mybatis.spring.*;

class UploadPersistenceTest {
    String adminUrl,user,password,database;DriverManagerDataSource source;UploadService service;UploadMapper mapper;
    @TempDir Path directory;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/uploads/UploadMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(UploadMapper.class);
        service=new UploadService(mapper,new CurrentUser(),Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")),new UploadWorkbookWriter(),new PartnerUploadForm(mapper),new DataSourceTransactionManager(source));ReflectionTestUtils.setField(service,"directory",directory.toString());
    }
    @AfterEach void removeIsolatedDatabase()throws Exception{if(source==null)return;try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
    long scalar(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    void execute(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String token(String[]...rows)throws Exception{return service.validate("B.7",new MockMultipartFile("file","거래처.xlsx","application/octet-stream",UploadApiTest.file(rows))).token();}
    Map<String,Long> counts()throws Exception{var counts=new LinkedHashMap<String,Long>();for(String table:List.of("mst.ptnr","sys.upld","sys.upld_err","sys.data_chg_hist","sys.acs_hist"))counts.put(table,scalar("SELECT count(*) FROM "+table));return counts;}
    @Test void successfulImportAndReplayPersistAllRowsInOrderAndSafeHistory()throws Exception{
        String[][] rows=new String[5][];for(int i=0;i<5;i++){rows[i]=UploadApiTest.customer("123456789"+i);if(i<3)rows[i][8]="무시한 은행";}
        long original=scalar("SELECT count(*) FROM mst.ptnr");String token=token(rows);assertThat(scalar("SELECT count(*) FROM sys.upld")).isZero();var result=service.apply(token);
        assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(original+5);assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE upld_id="+result.upldId()+" AND use_yn='Y'")).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM sys.upld_err WHERE upld_id="+result.upldId()+" AND err_svrt_cd='WARN'")).isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='INSERT' AND rsn_desc='엑셀 업로드 #"+result.upldId()+"' AND chg_user_id IS NULL")).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='UPLOAD' AND scrn_id='UI-15' AND user_id IS NULL")).isEqualTo(1);
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT ptnr_no FROM mst.ptnr WHERE upld_id="+result.upldId()+" ORDER BY ptnr_id")){for(int i=1;i<=5;i++){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("C-"+String.format("%03d",i));}}
        var before=counts();assertThat(service.apply(token)).isEqualTo(result);assertThat(counts()).isEqualTo(before);
    }
    @Test void secondBusinessInsertFailureRollsBackUploadWarningsAuditAndAccess()throws Exception{
        String[] first=UploadApiTest.customer("1234567890");first[8]="무시";String token=token(first,UploadApiTest.customer("2222222222"));Map<String,Long> before=counts();
        execute("ALTER TABLE mst.ptnr ADD CONSTRAINT reject_second_import CHECK (biz_reg_no != '222-22-22222') NOT VALID");assertThatThrownBy(()->service.apply(token)).isInstanceOf(RuntimeException.class);assertThat(counts()).isEqualTo(before);
        execute("ALTER TABLE mst.ptnr DROP CONSTRAINT reject_second_import");assertThat(service.apply(token).appliedRows()).isEqualTo(2);
    }
    @Test void staleKeyRollsBackWholeFile()throws Exception{String token=token(UploadApiTest.customer("1234567890"),UploadApiTest.customer("2222222222"));execute("INSERT INTO mst.ptnr(ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no,use_yn) VALUES('EXISTING','CUST','기등록','123-45-67890','N')");var before=counts();assertThatThrownBy(()->service.apply(token)).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(409);assertThat(e.code()).isEqualTo("STALE_VALIDATION");});assertThat(counts()).isEqualTo(before);}
    @Test void concurrentTokensWithSameKeyHaveOneCommitAndAtomicConflict()throws Exception{
        UploadMapper observed=org.mockito.Mockito.spy(mapper);
        service=new UploadService(observed,new CurrentUser(),Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")),new UploadWorkbookWriter(),new PartnerUploadForm(observed),new DataSourceTransactionManager(source));ReflectionTestUtils.setField(service,"directory",directory.toString());
        String a=token(UploadApiTest.customer("1234567890")),b=token(UploadApiTest.customer("1234567890"));CyclicBarrier start=new CyclicBarrier(2),checked=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        org.mockito.Mockito.doAnswer(invocation->{String value=mapper.duplicateName(invocation.getArgument(0));checked.await(10,TimeUnit.SECONDS);return value;}).when(observed).duplicateName(org.mockito.ArgumentMatchers.anyString());
        try {
            List<Future<String>> futures=new ArrayList<>();for(String token:List.of(a,b))futures.add(pool.submit(()->{start.await(10,TimeUnit.SECONDS);try{return service.apply(token).upldId();}catch(ApiException e){assertThat(e.status()).isEqualTo(409);return e.code();}}));
            List<String> results=new ArrayList<>();for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));assertThat(results).contains("STALE_VALIDATION");assertThat(results.stream().filter(s->!s.equals("STALE_VALIDATION"))).hasSize(1);
            assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.upld WHERE upld_stts_cd='APPLIED'")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void rejectionRecheckAndErrorWorkbookReadbackUseDatabaseCodesWithoutBusinessWrites()throws Exception{
        var row=UploadApiTest.customer("1234567890");row[4]="핀테크";var rejected=service.validate("B.7",new MockMultipartFile("file","거래처.xlsx","application/octet-stream",UploadApiTest.file(row)));
        assertThat(rejected.errorRows()).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.upld_err")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isZero();
        assertThat(service.errorFile(Long.parseLong(rejected.upldId())).bytes()).isNotEmpty();
        execute("INSERT INTO cmn.excel_map(excel_lbl_nm,trns_cd_grp_id,trns_cd,map_role_cd) VALUES('핀테크','INDUSTRY','SI','MAIN')");var checked=service.recheck(Long.parseLong(rejected.upldId()));assertThat(checked.errorRows()).isZero();assertThat(checked.warningRows()).isEqualTo(1);assertThat(checked.token()).isNotNull();assertThat(scalar("SELECT count(*) FROM sys.upld")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isZero();
        service.template("B.7");assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-15'")).isEqualTo(2);
    }
}
