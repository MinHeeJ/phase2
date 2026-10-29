package com.sicost.parts;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.sicost.apiops.*;
import com.sicost.common.api.ApiException;
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

class PartSettlementPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    static class TestApproval extends PartSettlementApproval {
        @Override public boolean approved() { return true; }
    }
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PartSettlementService service;
    final Key key = new Key(90001, "2026-10");
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/parts/PartSettlementMapper.xml"),
            new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"));
        factory.afterPropertiesSet(); var session = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, PartSettlementService.class, ProjectCostService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(PartSettlementMapper.class, () -> session.getMapper(PartSettlementMapper.class));
        context.registerBean(ProjectBoardMapper.class, () -> session.getMapper(ProjectBoardMapper.class));
        context.registerBean(PartSettlementApproval.class, TestApproval::new);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")));
        context.refresh(); service = context.getBean(PartSettlementService.class);
        sql("INSERT INTO mst.bsn_part(bsn_part_id,bsn_part_nm) VALUES(90001,'정산 시험 파트')");
        sql("INSERT INTO mst.bsn_part_std_hist(bsn_part_id,aply_bgn_ym,prft_dstrb_rt,bsn_fee_amt) VALUES(90001,'2026-10',40,0)");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(90001,'K-90001','정산 인력','FT')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(90001,'P-26901','정산 프로젝트','MO','RUN')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(90001,'A-90001',90001,90001,'BE','2026-10-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90001,'2026-10',1)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(90001,'2026-10-01',200)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,bsn_part_id,emp_se_cd,slry_amt) VALUES(90001,'2026-10',90001,'FT',100)");
        sql("UPDATE fin.mth_cls SET cls_stts_cd='OPEN' WHERE cls_ym='2026-10'");
    }
    @AfterEach void dropDatabase() throws Exception {
        if (context != null) context.close();
        if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    void sql(String text) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(text); }
    }
    String value(String text) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(text)) {
            r.next(); return r.getString(1);
        }
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('header',(SELECT json_agg(s ORDER BY stlm_id) FROM fin.part_stlm s),'detail',(SELECT json_agg(d ORDER BY stlm_dtl_id) FROM fin.part_stlm_dtl d),'history',(SELECT json_agg(h ORDER BY stlm_hist_id) FROM fin.part_stlm_hist h),'audit',(SELECT json_agg(a ORDER BY data_chg_hist_id) FROM sys.data_chg_hist a))::text");
    }
    @Test void cardRemarkAtomicThenConfirmSnapshotAndCancelPreservesDetailAndHistory() throws Exception {
        service.saveCard(key, 10, "시험 비고");
        var header = context.getBean(PartSettlementMapper.class).header(key);
        assertThat(header.card_remark()).isEqualTo("시험 비고");
        assertThat(header.corp_card_amt()).isEqualTo(10);
        service.confirmCalculated(key);
        assertThat(value("SELECT stlm_stts_cd FROM fin.part_stlm WHERE bsn_part_id=90001")).isEqualTo("CONFIRM");
        assertThat(value("SELECT part_prft_amt FROM fin.part_stlm WHERE bsn_part_id=90001")).isEqualTo("30");
        String frozen = snapshot();
        assertThatThrownBy(() -> service.saveCard(key, 99, "금지")).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(frozen);
        sql("UPDATE cst.mth_cst SET slry_amt=999 WHERE dev_id=90001");
        assertThat(service.get(key, "MONTH").months().get(0).part_prft_amt()).isEqualTo(30);
        service.cancelCalculated(key, "검토 후 재계산");
        assertThat(value("SELECT count(*) FROM fin.part_stlm_dtl")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM fin.part_stlm_hist")).isEqualTo("2");
        assertThat(value("SELECT stlm_stts_cd FROM fin.part_stlm WHERE bsn_part_id=90001")).isEqualTo("DRAFT");
    }
    @Test void approvedTestOnlyConcurrentConfirmSucceedsExactlyOnce() throws Exception {
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Callable<String> request = () -> {
            start.await();
            try { service.confirmCalculated(key); return "CONFIRM"; }
            catch (ApiException ex) { return ex.code(); }
        };
        try {
            var first = executor.submit(request); var second = executor.submit(request); start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("CONFIRM", "STALE_SETTLEMENT");
            assertThat(value("SELECT count(*) FROM fin.part_stlm_hist WHERE stlm_prcs_cd='CONFIRM'")).isEqualTo("1");
        } finally { executor.shutdownNow(); }
    }
    @Test void auditFailureRollsBackHeaderDetailsHistoryAndCard() throws Exception {
        service.saveCard(key, 10, "유지");
        String before = snapshot();
        sql("CREATE FUNCTION public.reject_settlement_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test audit failure'; END $$");
        sql("CREATE TRIGGER reject_settlement_audit BEFORE INSERT ON sys.data_chg_hist FOR EACH ROW EXECUTE FUNCTION public.reject_settlement_audit()");
        assertThatThrownBy(() -> service.confirmCalculated(key)).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void closedCancelAndBlankReasonHaveNoWrites() throws Exception {
        service.confirmCalculated(key);
        String before = snapshot();
        assertThatThrownBy(() -> service.cancelCalculated(key, " ")).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
        sql("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");
        assertThatThrownBy(() -> service.cancelCalculated(key, "취소"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
}
