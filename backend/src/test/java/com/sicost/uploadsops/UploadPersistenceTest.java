package com.sicost.uploadsops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.uploadsops.UploadModels.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

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
        Clock clock=Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));
        context.registerBean(UploadStorage.class,()->new UploadStorage(System.getProperty("java.io.tmpdir")+"/sicost/uploads/"+database,clock));context.registerBean(ObjectMapper.class,()->new ObjectMapper());context.refresh();service=context.getBean(UploadService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    long scalar(String sql)throws Exception {try(var connection=source.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    void sql(String sql)throws Exception {try(var connection=source.getConnection();var statement=connection.createStatement()){statement.execute(sql);}}
    Validation validate(String[]... rows)throws Exception{return service.validate("B.7",new MockMultipartFile("file","거래처.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",UploadApiTest.file(rows)));}
    long[] counts()throws Exception{return new long[]{scalar("SELECT count(*) FROM sys.upld"),scalar("SELECT count(*) FROM mst.ptnr"),scalar("SELECT count(*) FROM sys.upld_err"),scalar("SELECT count(*) FROM sys.data_chg_hist"),scalar("SELECT count(*) FROM sys.acs_hist")};}
    @Test void anonymousMultipartHttpValidationPersistsRejectedErrorsAndWarningsWithoutBusinessWrites()throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new UploadController(service))
            .setControllerAdvice(new com.sicost.common.api.ApiExceptionHandler())
            .addFilters(new com.sicost.common.api.RequestIdFilter()).build();
        String[] row=UploadApiTest.customer("1234500000");row[1]="";row[8]="무시되는 은행";
        long partners=scalar("SELECT count(*) FROM mst.ptnr"),history=scalar("SELECT count(*) FROM sys.data_chg_hist"),access=scalar("SELECT count(*) FROM sys.acs_hist");
        mvc.perform(multipart("/api/uploads/B.7/validate").file(new MockMultipartFile("file","거래처.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",UploadApiTest.file(row))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalRows").value(1))
            .andExpect(jsonPath("$.data.errorRows").value(1)).andExpect(jsonPath("$.data.warningRows").value(1))
            .andExpect(jsonPath("$.data.upldId").isString()).andExpect(jsonPath("$.data.token").doesNotExist())
            .andExpect(jsonPath("$.data.file_path_addr").doesNotExist());
        assertThat(scalar("SELECT count(*) FROM sys.upld WHERE upld_stts_cd='REJECTED'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.upld_err WHERE err_svrt_cd='ERROR'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.upld_err WHERE err_svrt_cd='WARN'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(partners);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access);
    }
    @Test void realMapperApplyReadbackAndIdempotencyAreAtomic()throws Exception {
        String[][] rows=new String[5][];for(int i=0;i<5;i++){rows[i]=UploadApiTest.customer("123450000"+i);if(i<3)rows[i][8]="무시";}
        long[] before=counts();Validation checked=validate(rows);assertThat(counts()).isEqualTo(before);Applied result=service.apply(checked.token());long id=Long.parseLong(result.upldId());
        assertThat(result.appliedRows()).isEqualTo(5);assertThat(scalar("SELECT count(*) FROM sys.upld WHERE upld_stts_cd='APPLIED' AND upld_id="+id)).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE use_yn='Y' AND upld_id="+id)).isEqualTo(5);assertThat(scalar("SELECT count(*) FROM sys.upld_err WHERE err_svrt_cd='WARN' AND upld_id="+id)).isEqualTo(3);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='INSERT' AND rsn_desc='엑셀 업로드 #"+id+"'")).isEqualTo(5);assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='UPLOAD' AND scrn_id='UI-15' AND user_id IS NULL")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE ptnr_no='C-001'")).isEqualTo(1);long[] applied=counts();assertThat(service.apply(checked.token())).isEqualTo(result);assertThat(counts()).isEqualTo(applied);
    }
    @Test void rejectedOriginalAppendAndLatestMappingRecheckNeverWriteBusinessRows()throws Exception {
        String[] row=UploadApiTest.customer("1234500000");row[4]="핀테크";long before=scalar("SELECT count(*) FROM mst.ptnr");Validation first=validate(row);assertThat(first.errorRows()).isEqualTo(1);assertThat(mapper.issues(Long.parseLong(first.upldId()))).hasSize(1);
        Validation second=service.recheck(first.upldId());assertThat(second.upldId()).isNotEqualTo(first.upldId());
        sql("INSERT INTO cmn.excel_map(excel_lbl_nm,trns_cd_grp_id,trns_cd,map_role_cd) VALUES('핀테크','INDUSTRY','SI','MAIN')");Validation third=service.recheck(first.upldId());assertThat(third.errorRows()).isZero();assertThat(third.warningRows()).isEqualTo(1);assertThat(third.token()).isNotNull();assertThat(scalar("SELECT count(*) FROM sys.upld")).isEqualTo(2);assertThat(mapper.original(Long.parseLong(first.upldId()))).isEqualTo(mapper.original(Long.parseLong(second.upldId())));assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(before);
    }
    @Test void secondBusinessInsertFailureRollsBackEveryLedgerHistoryWarningAndAccess()throws Exception {
        String[] first=UploadApiTest.customer("1234500000"),second=UploadApiTest.customer("1234500001");first[8]="무시";second[1]="실패행";Validation checked=validate(first,second);long[] before=counts();
        sql("ALTER TABLE mst.ptnr ADD CONSTRAINT reject_second_upload CHECK (ptnr_nm &lt;&gt; '실패행') NOT VALID".replace("&lt;&gt;","<>"));
        assertThatThrownBy(()->service.apply(checked.token())).isInstanceOf(RuntimeException.class);assertThat(counts()).isEqualTo(before);
        sql("ALTER TABLE mst.ptnr DROP CONSTRAINT reject_second_upload");assertThat(service.apply(checked.token()).appliedRows()).isEqualTo(2);
    }
    @Test void auditFailureRollsBackBusinessAndUpload()throws Exception {
        Validation checked=validate(UploadApiTest.customer("1234500000"));long[] before=counts();sql("ALTER TABLE sys.acs_hist ADD CONSTRAINT reject_upload_access CHECK (false) NOT VALID");assertThatThrownBy(()->service.apply(checked.token())).isInstanceOf(RuntimeException.class);assertThat(counts()).isEqualTo(before);
    }
    @Test void staleKeyRollsBackWholeFile()throws Exception {
        Validation checked=validate(UploadApiTest.customer("1234500000"),UploadApiTest.customer("1234500001"));sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(nextval('mst.seq_ptnr_id'),'C-existing','CUST','다른 등록','123-45-00000')");long[] before=counts();assertThatThrownBy(()->service.apply(checked.token())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("STALE_VALIDATION"));assertThat(counts()).isEqualTo(before);
    }
    @Test void concurrentSeparateTokensHaveOneCommitAndOneAtomicConflict()throws Exception {
        Validation first=validate(UploadApiTest.customer("1234500000")),second=validate(UploadApiTest.customer("1234500000"));CyclicBarrier start=new CyclicBarrier(2);ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results=new ArrayList<>();for(String token:List.of(first.token(),second.token()))results.add(executor.submit(()->{start.await(10,TimeUnit.SECONDS);try{service.apply(token);return "APPLIED";}catch(ApiException ex){return ex.code();}}));
            List<String> values=new ArrayList<>();for(Future<String> result:results)values.add(result.get(20,TimeUnit.SECONDS));assertThat(values).containsExactlyInAnyOrder("APPLIED","STALE_VALIDATION");assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.upld")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(1);
        } finally {executor.shutdownNow();}
    }
    @Test void downloadsAuditOnlyAfterRealWorkbookGenerated()throws Exception {
        service.template("B.7");assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND obj_desc='거래처_업로드양식.xlsx'")).isEqualTo(1);Validation bad=validate(UploadApiTest.customer("bad"));service.errorFile(bad.upldId());assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD'")).isEqualTo(2);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isZero();
    }
}
