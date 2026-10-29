package com.sicost.billings;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.billing.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import com.sicost.shared.SharedReadMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class BillingPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingService service;
    ObjectMapper json = new ObjectMapper();
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.create(s,database); }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mappers/**/*.xml"));
        factory.afterPropertiesSet(); var template=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,BillingService.class,BillingMutationService.class,
            BillingAmountService.class,AssignmentConcurrencyService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(BillingMapper.class,()->template.getMapper(BillingMapper.class));
        context.registerBean(BillingMutationMapper.class,()->template.getMapper(BillingMutationMapper.class));
        context.registerBean(AssignmentConcurrencyMapper.class,()->template.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(SharedReadMapper.class,()->template.getMapper(SharedReadMapper.class));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-31T15:01:00Z"),ZoneOffset.UTC));
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.refresh(); service=context.getBean(BillingService.class);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(900001,'P-BILL-TK','턴키 시험','TK',NULL),(900002,'P-BILL-MO','월 시험','MO','MONTH'),(900003,'P-BILL-DP','파견 시험','DP','HOUR')");
        sql("INSERT INTO prj.bill_stg(bill_stg_id,prj_id,stg_odr_no,bill_stg_nm,sply_amt,bill_plan_dt) VALUES(900001,900001,1,'착수금',1000,'2026-10-31'),(900002,900001,2,'잔금',2000,'2026-11-30')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,main_job_cd) VALUES(900001,'K-BILL-TEST','시험 인력','FT','DEV')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(900001,'A-BILL-TEST',900002,900001,'DEV','2026-10-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(900001,'2026-10',1.25)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(900001,'2026-10-01',1000)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close(); if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.drop(s,database); }
    }
    void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()) { s.execute(text); } }
    String value(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)) { r.next(); return r.getString(1); } }
    BillingModels.Input input(String text,boolean create) throws Exception { return BillingInput.parse(json.readTree(text),create); }
    BillingModels.BillingDetail createMonthly() throws Exception { return service.create(input("{\"project_id\":900002,\"month\":\"2026-10\"}",true)); }
    String snapshot() throws Exception { return value("SELECT json_build_object('bill',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b),'detail',(SELECT json_agg(d ORDER BY bill_dtl_id) FROM bill.bill_dtl d),'audit',(SELECT json_agg(a ORDER BY data_chg_hist_id) FROM sys.data_chg_hist a))::text"); }
    @Test void sourceSnapshotsSurviveSourcePriceChangeUntilExplicitRecalculation() throws Exception {
        var first=createMonthly(); long id=first.billing().bill_id();
        assertThat(first.billing().bill_no()).isEqualTo("B-2611-01");
        assertThat(first.details()).hasSize(1); assertThat(first.details().get(0).bill_amt()).isEqualTo(1250);
        sql("UPDATE asgn.untprc_hist SET bill_untprc_amt=2000 WHERE asgn_id=900001");
        var ordinary=service.update(id,input("{\"txinv_no\":\"수동번호\",\"vat_amt\":7}",false));
        assertThat(ordinary.details()).isEqualTo(first.details());
        var recalculated=service.update(id,input("{\"recalculate\":true,\"sply_amt\":2400}",false));
        assertThat(recalculated.details().get(0).bill_amt()).isEqualTo(2500);
        assertThat(recalculated.billing().sply_amt()).isEqualTo(2400); assertThat(recalculated.billing().vat_amt()).isEqualTo(7);
        assertThat(recalculated.warnings()).anyMatch(w->w.contains("다릅니다"));
        assertThat(value("SELECT mm_qty FROM asgn.asgn_mth WHERE asgn_id=900001")).isEqualTo("1.25");
    }
    @ParameterizedTest @ValueSource(strings={"bill.bill","bill.bill_dtl","sys.data_chg_hist"})
    void anyCreateWriteFailureRollsBackHeaderDetailsNumberAndAudit(String table) throws Exception {
        String before=snapshot(); sql("ALTER TABLE "+table+" ADD CONSTRAINT reject_write CHECK(false) NOT VALID");
        assertThatThrownBy(()->createMonthly()).isInstanceOf(RuntimeException.class); assertThat(snapshot()).isEqualTo(before);
        sql("ALTER TABLE "+table+" DROP CONSTRAINT reject_write");
        assertThat(createMonthly().billing().bill_no()).isEqualTo("B-2611-01");
    }
    @Test void failedAuditRollsBackUpdateAndRecalculatedDetails() throws Exception {
        long id=createMonthly().billing().bill_id(); String before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_write CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.update(id,input("{\"sply_amt\":999,\"recalculate\":true}",false))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void concurrentNumbersAreUniqueAndSameStageHasOneWinner() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();return service.create(input("{\"project_id\":900001,\"stage_id\":900001}",true));});
            var b=pool.submit(()->{start.await();return service.create(input("{\"project_id\":900001,\"stage_id\":900002}",true));});
            start.countDown(); var one=a.get(15,TimeUnit.SECONDS); var two=b.get(15,TimeUnit.SECONDS);
            assertThat(one.billing().bill_no()).isNotEqualTo(two.billing().bill_no());
            assertThat(one.details()).isEmpty(); assertThat(two.details()).isEmpty();
            assertThatThrownBy(()->service.create(input("{\"project_id\":900001,\"stage_id\":900001}",true)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ALREADY_BILLED"));
        } finally { pool.shutdownNow(); }
    }
    @Test void closedMonthAndConfirmedUpdatePreserveAllRows() throws Exception {
        long id=createMonthly().billing().bill_id(); sql("UPDATE bill.bill SET bill_stts_cd='CONFIRM' WHERE bill_id="+id);
        String before=snapshot(); assertThatThrownBy(()->service.update(id,input("{\"sply_amt\":999}",false))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
        sql("UPDATE bill.bill SET bill_stts_cd='DRAFT' WHERE bill_id="+id);
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        before=snapshot(); assertThatThrownBy(()->service.update(id,input("{\"recalculate\":true}",false)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void filteredCountVirtualPlansAndConfirmedOnlyReceivablesPersistAcrossSessions() throws Exception {
        createMonthly();
        var plan=service.list(Map.of("year","2026","type","TK","status","PLAN","q","턴키 시험"));
        assertThat(plan.total()).isEqualTo(2); assertThat(plan.items()).hasSize(2);
        assertThat(plan.items()).allMatch(r->r.bill_id()==null&&r.bill_dt()==null&&r.virtual_plan()&&r.receivable_amt()==0);
        var page=service.list(Map.of("year","2026","type","MO","q","월 시험"));
        assertThat(page.total()).isEqualTo(1); assertThat(page.items().get(0).receivable_amt()).isZero();
        assertThat(service.get(page.items().get(0).bill_id()).details()).hasSize(1);
    }
}
