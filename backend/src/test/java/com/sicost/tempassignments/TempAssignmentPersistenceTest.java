package com.sicost.tempassignments;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.tempassignments.TempAssignmentModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.CurrentUser;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class TempAssignmentPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    TempAssignmentService service;
    TempAssignmentMapper mapper;
    org.springframework.test.web.servlet.MockMvc mvc;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null && !adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null && password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()) {com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/tempassignments/TempAssignmentMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(TempAssignmentMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,TempAssignmentService.class,CurrentUser.class);
        context.registerBean(TempAssignmentMapper.class,() -> mapper);context.registerBean(ObjectMapper.class,() -> json);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,() -> new DataSourceTransactionManager(source));context.refresh();
        service=context.getBean(TempAssignmentService.class);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new TempAssignmentController(service,json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(1,'C-TEST','CUST','고객사','123-45-67890')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,main_job_cd,grd_cd) VALUES(1,'K-TEST1','첫 인력','FT','BE','MD'),(2,'K-TEST2','둘째 인력','FT','BE','MD'),(3,'K-TEST3','셋째 인력','FT','BE','MD')");
        sql("INSERT INTO bsn.oppt(oppt_id,oppt_no,oppt_nm,cust_ptnr_id,win_prblt_rt) VALUES(1,'O-TEST1','첫 기회',1,60),(2,'O-TEST2','다른 기회',1,60)");
        sql("INSERT INTO bsn.oppt_need(need_id,oppt_id,job_cd,grd_cd,psn_cnt,bgn_ym,mnth_qty) VALUES(1,1,'BE','MD',2,'2026-11',3),(2,2,'BE','MD',2,'2026-11',3)");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd,cntrct_bgn_dt,cntrct_end_dt) VALUES(1,'P-TEST','기존 확정',1,'MO','RUN','2026-01-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_rt) VALUES(1,'A-TEST',1,1,'BE','2026-01-01','2026-12-31',0.70)");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(1,'2026-11',0.70),(1,'2026-12',0.70)");
    }
    @AfterEach void cleanup() throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s,database);}
    }
    void sql(String query,Object... args){new JdbcTemplate(source).update(query,args);}
    long scalar(String query,Object... args){return new JdbcTemplate(source).queryForObject(query,Long.class,args);}
    String value(String query){return new JdbcTemplate(source).queryForObject(query,String.class);}
    Write input(long need,long dev,String from,String to,String mm,String origin){return new Write(need,dev,from,to,new BigDecimal(mm),origin);}
    TempAssignment create(long need,long dev){return service.create(input(need,dev,"2026-11","2027-01","0.40","MANUAL"));}
    String sources() {
        return value("SELECT json_build_array((SELECT json_agg(a ORDER BY asgn_id) FROM asgn.asgn a),(SELECT json_agg(m ORDER BY asgn_id,work_ym) FROM asgn.asgn_mth m),(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b),(SELECT json_agg(c ORDER BY dev_id,cst_ym) FROM cst.mth_cst c),(SELECT json_agg(c ORDER BY cls_ym) FROM fin.mth_cls c),(SELECT json_agg(c ORDER BY pl_ym) FROM fin.mth_pl c))::text");
    }
    String businessSnapshot(){return value("SELECT json_build_array((SELECT json_agg(t ORDER BY tmpr_asgn_id) FROM asgn.tmpr_asgn t),(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");}
    @Test void createAndReleaseRoundTripPreservesActualSourcesAndWritesAtomicAudit() throws Exception {
        String before=sources();var row=create(1,1);
        assertThat(row.warnings()).anyMatch(s -> s.contains("1.10"));assertThat(sources()).isEqualTo(before);
        assertThat(mapper.row(row.id()).status()).isEqualTo("ACTIVE");assertThat(mapper.row(row.id()).mtch_scr()).isNull();
        mvc.perform(get("/api/temp-assignments").param("need_id","1").param("dev_id","1").param("oppt_id","1").param("from","2026-12").param("to","2026-12"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(delete("/api/temp-assignments/{id}",row.id())).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RELEASED"));
        assertThat(service.list(Map.of("status","ACTIVE")).total()).isZero();assertThat(service.list(Map.of("status","RELEASED")).total()).isEqualTo(1);
        assertThat(sources()).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='asgn.tmpr_asgn' AND chg_user_id IS NULL")).isEqualTo(2);
        assertThat(value("SELECT bfr_val::json->>'status' FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).isEqualTo("ACTIVE");
        assertThat(value("SELECT aft_val::json->>'status' FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).isEqualTo("RELEASED");
    }
    @Test void nextScoreStoredFromAuthoritativeMatchingSources() throws Exception {
        var row=service.create(input(1,1,"2027-01","2027-01","0.01","NEXT"));
        assertThat(row.mtch_scr()).isEqualByComparingTo("100");assertThat(mapper.row(row.id()).mtch_scr()).isEqualByComparingTo(row.mtch_scr());
    }
    @ParameterizedTest @ValueSource(strings={"0.01","1.00"}) void validMmBoundariesPersist(String mm){var r=service.create(input(1,1,"2026-11","2026-11",mm,"MANUAL"));assertThat(mapper.row(r.id()).mth_mm_qty()).isEqualByComparingTo(mm);}
    @ParameterizedTest @ValueSource(strings={"0","1.01","0.001"}) void invalidMmNeverPersists(String mm){String before=businessSnapshot();assertThatThrownBy(() -> service.create(input(1,1,"2026-11","2026-11",mm,"MANUAL"))).isInstanceOf(ApiException.class);assertThat(businessSnapshot()).isEqualTo(before);}
    @Test void capacityAndOtherNeedAreWarningsNotBlockers(){create(1,1);create(1,2);var third=create(1,3);assertThat(third.warnings()).contains("필요 인원을 초과한 가배정입니다.");var other=create(2,1);assertThat(other.warnings()).contains("다른 필요 인력과 가배정 기간이 겹칩니다.");assertThat(scalar("SELECT count(*) FROM asgn.tmpr_asgn WHERE tmpr_asgn_stts_cd='ACTIVE'")).isEqualTo(4);}
    @Test void leaveAndRetirementAreWarnOnly(){sql("UPDATE mst.dev SET rsgn_dt='2026-12-01' WHERE dev_id=1");sql("INSERT INTO mst.dev_work_stts_hist(dev_id,work_stts_cd,bgn_dt,end_dt) VALUES(1,'LEAVE','2026-11-01','2026-11-30')");var r=create(1,1);assertThat(r.warnings()).contains("휴직 기간 가배정입니다.","퇴사 기간 가배정입니다.");}
    @Test void inclusiveOverlapRejectsButAdjacentMonthsSucceed(){create(1,1);assertThatThrownBy(() -> service.create(input(1,1,"2027-01","2027-02","0.10","MANUAL"))).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("DUPLICATE_TEMP_ASSIGNMENT"));service.create(input(1,1,"2027-02","2027-03","0.10","MANUAL"));assertThat(scalar("SELECT count(*) FROM asgn.tmpr_asgn")).isEqualTo(2);}
    @Test void concurrentOverlapLeavesExactlyOneActiveAndOneConflict() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        Callable<Integer> request=() -> {barrier.await(5,TimeUnit.SECONDS);try{create(1,1);return 200;}catch(ApiException e){assertThat(e.code()).isEqualTo("DUPLICATE_TEMP_ASSIGNMENT");return e.status();}};
        try {var a=pool.submit(request);var b=pool.submit(request);assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);}
        finally{pool.shutdownNow();}
        assertThat(scalar("SELECT count(*) FROM asgn.tmpr_asgn WHERE tmpr_asgn_stts_cd='ACTIVE'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='asgn.tmpr_asgn'")).isEqualTo(1);
    }
    @Test void releaseAndConvertedConflictPreserveConfirmedAssignment(){var r=create(1,1);sql("UPDATE asgn.tmpr_asgn SET tmpr_asgn_stts_cd='CONVERTED',asgn_id=1 WHERE tmpr_asgn_id=?",r.id());String actual=sources(),before=businessSnapshot();assertThatThrownBy(() -> service.release(r.id())).isInstanceOf(ApiException.class);assertThat(businessSnapshot()).isEqualTo(before);assertThat(sources()).isEqualTo(actual);}
    @Test void duplicateReleaseDoesNotAppendHistory(){var r=create(1,1);service.release(r.id());String before=businessSnapshot();assertThatThrownBy(() -> service.release(r.id())).isInstanceOf(ApiException.class);assertThat(businessSnapshot()).isEqualTo(before);}
    @Test void readOnlyFiltersCountsEmptyAndRepeatLeaveAllSnapshotsUnchanged(){create(1,1);String before=sources(),business=businessSnapshot();var q=Map.of("oppt_id","1","status","ACTIVE","from","2027-01","to","2027-01");assertThat(service.list(q).total()).isEqualTo(1);assertThat(service.list(q).items()).isEqualTo(service.list(q).items());assertThat(service.list(Map.of("need_id","2")).total()).isZero();assertThat(service.list(Map.of("need_id","2")).items()).isEmpty();assertThat(sources()).isEqualTo(before);assertThat(businessSnapshot()).isEqualTo(business);}
    @Test void auditFailureRollsBackCreate(){String before=businessSnapshot();sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_temp CHECK(tbl_nm!='asgn.tmpr_asgn') NOT VALID");assertThatThrownBy(() -> create(1,1)).isInstanceOf(RuntimeException.class);assertThat(businessSnapshot()).isEqualTo(before);}
    @Test void auditFailureRollsBackRelease(){var r=create(1,1);String before=businessSnapshot();sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_temp CHECK(tbl_nm!='asgn.tmpr_asgn') NOT VALID");assertThatThrownBy(() -> service.release(r.id())).isInstanceOf(RuntimeException.class);assertThat(businessSnapshot()).isEqualTo(before);}
}
