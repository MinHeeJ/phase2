package com.sicost.costsops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.costallocation.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RenegotiationPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    RenegotiationService service;
    RenegotiationExportService exports;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
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
        factory.setMapperLocations(new ClassPathResource("mappers/costallocation/CostAllocationMapper.xml"),
            new ClassPathResource("mappers/costsops/RenegotiationMapper.xml"));
        factory.afterPropertiesSet(); var session = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.register(CostAllocationService.class, CostAllocationEngine.class, RenegotiationService.class,
            RenegotiationExportService.class, ExcelExportService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(CostAllocationMapper.class, () -> session.getMapper(CostAllocationMapper.class));
        context.registerBean(RenegotiationMapper.class, () -> session.getMapper(RenegotiationMapper.class));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        context.refresh(); service = context.getBean(RenegotiationService.class); exports = context.getBean(RenegotiationExportService.class);
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(90001,'K-90001','협상 인력','FL')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(90001,'P-26901','유지보수','MO','RUN')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(90001,'A-90001',90001,90001,'BE','2026-10-01','2027-04-29')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90001,'2026-10',1.00)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(90001,'2026-10-01',100)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(90001,'2026-10','FL',90)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    void sql(String text) throws Exception { try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(text); } }
    String value(String text) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(text)) { r.next(); return r.getString(1); }
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('salary',(SELECT json_agg(s ORDER BY mth_cst_id) FROM cst.mth_cst s),'mm',(SELECT json_agg(m ORDER BY asgn_id,work_ym) FROM asgn.asgn_mth m),'prices',(SELECT json_agg(h ORDER BY asgn_id,aply_bgn_dt) FROM asgn.untprc_hist h),'snapshots',(SELECT json_agg(c ORDER BY cst_aloc_id) FROM cst.cst_aloc c),'history',(SELECT count(*) FROM sys.data_chg_hist))::text");
    }
    @Test void actualMapperProjectsRecordAndQueryAndSimulationAreZeroWrite() throws Exception {
        String before = snapshot();
        var result = service.list(Map.of());
        assertThat(result.total()).isEqualTo(1);
        var row = result.items().get(0);
        assertThat(row.asgn_id()).isEqualTo(90001);
        assertThat(row.dev_id()).isEqualTo(90001);
        assertThat(row.dev_nm()).isEqualTo("협상 인력");
        assertThat(row.emp_se_cd()).isEqualTo("FL");
        assertThat(row.prj_id()).isEqualTo(90001);
        assertThat(row.prj_nm()).isEqualTo("유지보수");
        assertThat(row.end_dt()).isEqualTo(LocalDate.parse("2027-04-29"));
        assertThat(row.bill_untprc_amt()).isEqualTo(100);
        assertThat(row.mm_qty()).isEqualByComparingTo("1.00");
        assertThat(row.lbr_cst_amt()).isEqualTo(90);
        assertThat(row.margin_rt()).isEqualByComparingTo("10");
        assertThat(service.list(Map.of("raiseRate", "50")).summary().annual_profit_increase_amt()).isEqualByComparingTo("600");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(value("SELECT count(*) FROM sys.acs_hist")).isEqualTo("0");
    }
    @ParameterizedTest @ValueSource(strings = {
        "UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=90001",
        "UPDATE asgn.asgn SET asgn_stts_cd='ENDED' WHERE asgn_id=90001",
        "UPDATE asgn.asgn SET asgn_stts_cd='REPLACED' WHERE asgn_id=90001",
        "UPDATE asgn.asgn SET asgn_se_cd='FREE' WHERE asgn_id=90001",
        "UPDATE asgn.asgn SET end_dt='2026-10-28' WHERE asgn_id=90001",
        "UPDATE asgn.asgn SET bgn_dt='2026-10-30' WHERE asgn_id=90001",
        "UPDATE asgn.asgn SET end_dt='2027-04-30' WHERE asgn_id=90001",
        "UPDATE prj.prj SET cntrct_ty_cd='TK' WHERE prj_id=90001",
        "UPDATE prj.prj SET cntrct_ty_cd='IN' WHERE prj_id=90001",
        "UPDATE prj.prj SET prj_stts_cd='HOLD' WHERE prj_id=90001"
    })
    void excludedPopulationDoesNotAppear(String mutation) throws Exception {
        sql(mutation);
        assertThat(service.list(Map.of()).items()).isEmpty();
    }
    @Test void todayBoundaryAndDispatchAreIncludedAndFiltersAgreeWithTotal() throws Exception {
        sql("UPDATE asgn.asgn SET bgn_dt='2026-10-29',end_dt='2026-10-29' WHERE asgn_id=90001");
        sql("UPDATE prj.prj SET cntrct_ty_cd='DP' WHERE prj_id=90001");
        assertThat(service.list(Map.of("empType", "FL", "projectId", "90001")).total()).isEqualTo(1);
        assertThat(service.list(Map.of("empType", "FT")).total()).isZero();
    }
    @Test void monthlyPriceChangesRequeryOpenButCannotRewriteClosedAmountsOrPrices() throws Exception {
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(90001,'2026-10-29',110)");
        var open = service.list(Map.of("marginBelow", "20")).items().get(0);
        assertThat(open.bill_untprc_amt()).isEqualTo(110);
        sql("INSERT INTO cst.cst_aloc(cst_ym,dev_id,prj_id,asgn_id,aloc_ty_cd,mm_qty,tot_mm_qty,aloc_rt,lbr_cst_amt,drct_expns_amt,bill_untprc_amt) VALUES('2026-10',90001,90001,90001,'PROJECT',1,1,1,90,0,100)");
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        String before = snapshot();
        var closed = service.list(Map.of()).items().get(0);
        assertThat(closed.bill_untprc_amt()).isEqualTo(100);
        assertThat(closed.lbr_cst_amt()).isEqualTo(90);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void missingClosedSnapshotRaisesConflictAndLeavesAllInputsUnchanged() throws Exception {
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        String before = snapshot();
        assertThatThrownBy(() -> service.list(Map.of())).isInstanceOfSatisfying(com.sicost.common.api.ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void downloadCommitsOneAuditWhileLeavingSourcesUnchanged() throws Exception {
        String before = snapshot();
        byte[] bytes = exports.export(Map.of("page", "1", "size", "20")).bytes();
        assertThat(bytes).startsWith((byte) 'P', (byte) 'K');
        assertThat(value("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-32' AND user_id IS NULL")).isEqualTo("1");
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void failingAuditRollsBackTheDownloadTransaction() throws Exception {
        sql("CREATE FUNCTION public.reject_renegotiation_download() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit unavailable'; END $$");
        sql("CREATE TRIGGER reject_renegotiation_download AFTER INSERT ON sys.acs_hist FOR EACH ROW EXECUTE FUNCTION public.reject_renegotiation_download()");
        String before = snapshot();
        assertThatThrownBy(() -> exports.export(Map.of())).isInstanceOf(RuntimeException.class);
        assertThat(value("SELECT count(*) FROM sys.acs_hist")).isEqualTo("0");
        assertThat(snapshot()).isEqualTo(before);
    }
}
