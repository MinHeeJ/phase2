package com.sicost.parts;

import com.sicost.common.api.ApiException;
import com.sicost.common.costallocation.*;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static com.sicost.parts.PartSettlementModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PartSettlementPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PartSettlementService service;
    PartSettlementMapper mapper;
    final Key key=new Key(98001,"2026-10");
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null && !adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null && password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/parts/PartSettlementMapper.xml"),
            new ClassPathResource("mappers/costallocation/CostAllocationMapper.xml"));
        factory.afterPropertiesSet(); var session=new SqlSessionTemplate(factory.getObject());
        mapper=spy(session.getMapper(PartSettlementMapper.class));
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,PartSettlementService.class,CostAllocationService.class,
            CostAllocationEngine.class,PermissionService.class,CurrentUser.class);
        context.registerBean(PartSettlementMapper.class,()->mapper);
        context.registerBean(CostAllocationMapper.class,()->session.getMapper(CostAllocationMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(PartSettlementApprovalPolicy.class,()->new PartSettlementApprovalPolicy() {
            @Override public boolean approved(Key key,Standard standard) { return true; }
        });
        context.refresh(); service=context.getBean(PartSettlementService.class);
        sql("INSERT INTO mst.bsn_part(bsn_part_id,bsn_part_nm) VALUES(98001,'정산 시험 파트')");
        sql("INSERT INTO mst.bsn_part_std_hist(bsn_part_id,aply_bgn_ym,ldr_nm,prft_dstrb_rt,bsn_fee_amt) VALUES(98001,'2026-01','시험 파트장',40,0)");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,bsn_part_id) VALUES(98001,'K-98001','시험 인력','FL',98001)");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(98001,'P-26981','시험 프로젝트','MO','RUN')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(98001,'A-98001',98001,98001,'BE','2026-01-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(98001,'2026-10',1.00)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(98001,'2026-01-01',200)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,duty_se_cd,bsn_part_id,slry_amt) VALUES(98001,'2026-10','FL','DIRECT',98001,100)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close(); if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement()) { s.execute(text); }
    }
    String value(String text) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)) { r.next(); return r.getString(1); }
    }
    @Test void cardNoteSurvivesFreshReadAndClearAppendsAudit() throws Exception {
        service.card(key,new Card(10,"식대",true));
        assertThat(service.get(key,"MONTH").note()).isEqualTo("식대");
        service.card(key,new Card(10,"",true));
        assertThat(service.get(key,"MONTH").note()).isEmpty();
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='fin.part_stlm' AND clmn_nm='corp_card_amt'")).isEqualTo("2");
        assertThat(value("SELECT count(*) FROM fin.part_stlm WHERE bsn_part_id=98001 AND stlm_ym='2026-10'")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='fin.part_stlm' AND chg_user_id IS NOT NULL")).isEqualTo("0");
    }
    @Test void confirmationIsFrozenAgainstSourceAndHistoryChangesAndCancelKeepsHistory() throws Exception {
        service.card(key,new Card(10,"비고",true));
        var frozen=service.confirm(key);
        assertThat(frozen.settlement().part_prft_amt()).isEqualTo(30L);
        sql("UPDATE cst.mth_cst SET slry_amt=999 WHERE dev_id=98001 AND cst_ym='2026-10'");
        sql("UPDATE mst.bsn_part_std_hist SET prft_dstrb_rt=99 WHERE bsn_part_id=98001");
        assertThat(service.get(key,"MONTH").settlement()).isEqualTo(frozen.settlement());
        assertThat(service.get(key,"MONTH").items()).isEqualTo(frozen.items());
        var trend=service.get(key,"TREND");
        assertThat(trend.trend()).hasSize(5);
        assertThat(trend.trend().get(4).values().get(9)).isEqualByComparingTo("40");
        assertThatThrownBy(()->service.card(key,new Card(999,null,false)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PART_ALREADY_CONFIRMED"));
        service.cancel(key,"정정");
        assertThat(value("SELECT count(*) FROM fin.part_stlm_hist")).isEqualTo("2");
        assertThat(value("SELECT count(*) FROM fin.part_stlm_dtl")).isEqualTo("1");
        assertThat(service.get(key,"MONTH").settlement().stlm_stts_cd()).isEqualTo("DRAFT");
    }
    @Test void auditFailureRollsBackNewHeaderDetailsAndTransition() throws Exception {
        doThrow(new IllegalStateException("audit unavailable")).when(mapper).audit(any());
        assertThatThrownBy(()->service.confirm(key)).isInstanceOf(IllegalStateException.class);
        assertThat(value("SELECT count(*) FROM fin.part_stlm WHERE bsn_part_id=98001")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM fin.part_stlm_dtl")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM fin.part_stlm_hist")).isEqualTo("0");
    }
    @Test void twoConcurrentConfirmationsCommitExactlyOneSnapshotAndHistory() throws Exception {
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        Callable<String> confirm=()->{
            start.await();
            try { service.confirm(key); return "CONFIRM"; }
            catch(ApiException e) { return e.code(); }
        };
        try {
            var a=pool.submit(confirm); var b=pool.submit(confirm); start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("CONFIRM","STALE_SETTLEMENT");
            assertThat(value("SELECT count(*) FROM fin.part_stlm_hist WHERE stlm_prcs_cd='CONFIRM'")).isEqualTo("1");
            assertThat(value("SELECT count(*) FROM fin.part_stlm_dtl")).isEqualTo("1");
        } finally { pool.shutdownNow(); }
    }
    @Test void queryNeverCreatesHeaderAndUsesMonthlyPartRatherThanCurrentDeveloperPart() throws Exception {
        sql("UPDATE mst.dev SET bsn_part_id=NULL,emp_se_cd='OS' WHERE dev_id=98001");
        var detail=service.get(key,"MONTH");
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).emp_se_cd()).isEqualTo("FL");
        assertThat(value("SELECT count(*) FROM fin.part_stlm WHERE bsn_part_id=98001")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='fin.part_stlm'")).isEqualTo("0");
    }
}
