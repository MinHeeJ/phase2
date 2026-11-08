package com.sicost.billings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.billings.BillingModels.*;

class BillingPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingService service;
    BillingMapper mapper;
    long actor;
    SecurityPrincipal principal;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/billings/BillingMapper.xml"),
            new ClassPathResource("mappers/security/SecurityStateMapper.xml")); factory.afterPropertiesSet();
        var template = new SqlSessionTemplate(factory.getObject());
        mapper = template.getMapper(BillingMapper.class);
        var security = template.getMapper(SecurityStateMapper.class);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        actor = jdbc.queryForObject("INSERT INTO sys.user_info(lgn_id,pswd_hash,user_nm,pswd_chg_need_yn) VALUES(?,?,?,'N') RETURNING user_id",
            Long.class, "billing-" + UUID.randomUUID(), new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(UUID.randomUUID().toString()), "청구 시험 사용자");
        jdbc.update("INSERT INTO sys.user_role(user_id,role_id) SELECT ?,role_id FROM sys.role WHERE role_nm='재무 담당'", actor);
        principal = new SecurityPrincipal(actor, "청구 시험 사용자", security.roles(actor), "a".repeat(64), false);
        bindPrincipal();
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, BillingService.class, BillingAmountService.class, CurrentUser.class, AuthorizationService.class);
        context.registerBean(BillingMapper.class, () -> mapper);
        context.registerBean(SecurityStateMapper.class, () -> security);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        context.refresh(); service = context.getBean(BillingService.class);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(1,'P-2601','월단위','MO','MONTH'),(2,'P-2602','턴키','TK','STAGE'),(3,'P-2603','턴키2','TK','STAGE')");
        sql("INSERT INTO prj.bill_stg(bill_stg_id,prj_id,stg_odr_no,bill_stg_nm,sply_amt,bill_plan_dt) VALUES(1,2,1,'착수금',5000,'2026-10-01'),(2,3,1,'착수금',7000,'2026-10-01')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'K-0001','실제 인력','FT'),(2,'K-0002','계약 인력','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,cntrct_dev_id,job_cd,bgn_dt,end_dt) VALUES(1,'A-0001',1,1,2,'BE','2026-10-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(1,'2026-10',0.50)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(1,'2026-10-01',2000),(1,'2026-11-01',9000)");
    }
    @AfterEach void cleanup() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        if (context != null) context.close(); if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }
    void sql(String text) throws Exception { try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(text); } }
    String value(String sql, Object... args) { return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql, String.class, args); }
    Input input(String body, boolean create) throws Exception { return BillingInput.parse(json.readTree(body), create); }
    void bindPrincipal() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
    <T> T authenticated(Callable<T> work) throws Exception {
        bindPrincipal();
        try { return work.call(); }
        finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    BillingDetail monthly() throws Exception { return service.create(input("{\"project_id\":1,\"month\":\"2026-10\"}", true)); }
    String snapshot() {
        return value("SELECT json_build_object('b',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b),'d',(SELECT json_agg(d ORDER BY bill_dtl_id) FROM bill.bill_dtl d),'h',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    @Test void realMaterializationCreateReadUpdateAndReloadPreserveIdentityAndSnapshots() throws Exception {
        var created = monthly(); long id = created.billing().bill_id();
        assertThat(created.billing().bill_no()).isEqualTo("B-2610-01");
        assertThat(created.details().get(0).dev_id()).isEqualTo(1);
        assertThat(created.details().get(0).cntrct_dev_id()).isEqualTo(2);
        assertThat(created.details().get(0).untprc_amt()).isEqualTo(2000);
        sql("UPDATE asgn.asgn_mth SET mm_qty=1 WHERE asgn_id=1");
        service.update(id, input("{\"sply_amt\":1234,\"vat_amt\":7,\"bill_dt\":\"2026-11-01\"}", false));
        assertThat(service.get(id).details().get(0).bill_amt()).isEqualTo(1000);
        assertThat(service.get(id).billing().atrb_ym()).isEqualTo("2026-10");
        assertThat(service.get(id).details().get(0).work_ym()).isEqualTo("2026-10");
        var recalculated = service.update(id, input("{\"recalculate\":true}", false));
        assertThat(recalculated.details().get(0).bill_amt()).isEqualTo(2000);
        assertThat(recalculated.billing().vat_amt()).isEqualTo(7);
        assertThat(value("SELECT chg_user_id FROM sys.data_chg_hist LIMIT 1")).isEqualTo(Long.toString(actor));
        assertThat(value("SELECT count(*) FROM bill.bill_dtl WHERE bill_id=?", id)).isEqualTo("1");
        var otherSession = new SqlSessionFactoryBean(); otherSession.setDataSource(source);
        otherSession.setMapperLocations(new ClassPathResource("mappers/billings/BillingMapper.xml")); otherSession.afterPropertiesSet();
        assertThat(new SqlSessionTemplate(otherSession.getObject()).getMapper(BillingMapper.class).row(id)).isEqualTo(recalculated.billing());
    }
    @Test void virtualPlansAreReadOnlyAndFilteredCountMatches() throws Exception {
        String before = snapshot();
        String months = value("SELECT json_agg(c ORDER BY cls_ym)::text FROM fin.mth_cls c");
        var query = BillingQuery.parse(Map.of("year", "2026", "type", "TK", "q", "턴키"), Clock.systemUTC());
        var page = service.list(query);
        assertThat(page.total()).isEqualTo(2); assertThat(page.items()).hasSize(2).allMatch(BillingRow::virtual_plan);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(value("SELECT json_agg(c ORDER BY cls_ym)::text FROM fin.mth_cls c")).isEqualTo(months);
        assertThat(value("SELECT count(*) FROM bill.bill")).isEqualTo("0");
        service.create(input("{\"project_id\":2,\"stage_id\":1}", true));
        var after = service.list(query);
        assertThat(after.total()).isEqualTo(2); assertThat(after.items()).filteredOn(BillingRow::virtual_plan).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings = {"header", "detail", "audit"})
    void everyCreateFailureRollsBackHeaderDetailsAuditAndNumberReservation(String failure) throws Exception {
        String before = snapshot();
        String table = switch (failure) { case "header" -> "bill.bill"; case "detail" -> "bill.bill_dtl"; default -> "sys.data_chg_hist"; };
        sql("ALTER TABLE " + table + " ADD CONSTRAINT reject_billing CHECK(false) NOT VALID");
        assertThatThrownBy(this::monthly).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
        sql("ALTER TABLE " + table + " DROP CONSTRAINT reject_billing");
        assertThat(monthly().billing().bill_no()).isEqualTo("B-2610-01");
    }
    @Test void recalculationAuditFailureRollsBackBothHeaderAndDetail() throws Exception {
        var row = monthly(); sql("UPDATE asgn.asgn_mth SET mm_qty=1 WHERE asgn_id=1");
        String before = snapshot(); sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_billing CHECK(false) NOT VALID");
        assertThatThrownBy(() -> service.update(row.billing().bill_id(), input("{\"recalculate\":true,\"vat_amt\":9}", false))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void confirmedAndClosedWritesLeaveAllSnapshotsUnchanged() throws Exception {
        var row = monthly(); long id = row.billing().bill_id();
        sql("UPDATE bill.bill SET bill_stts_cd='CONFIRM' WHERE bill_id=" + id); String before = snapshot();
        assertThatThrownBy(() -> service.update(id, input("{\"sply_amt\":3}", false))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("STALE_BILL"));
        assertThat(snapshot()).isEqualTo(before);
        sql("UPDATE bill.bill SET bill_stts_cd='DRAFT' WHERE bill_id=" + id);
        sql("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'"); before = snapshot();
        assertThatThrownBy(() -> service.update(id, input("{\"bill_dt\":\"2026-11-01\"}", false))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void concurrentCreationsHaveDistinctNumbersAndDuplicateStageHasOneWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> authenticated(() -> { start.await(); return service.create(input("{\"project_id\":2,\"stage_id\":1}", true)); }));
            var b = pool.submit(() -> authenticated(() -> { start.await(); return service.create(input("{\"project_id\":3,\"stage_id\":2}", true)); }));
            start.countDown(); assertThat(a.get(20, TimeUnit.SECONDS).billing().bill_no()).isNotEqualTo(b.get(20, TimeUnit.SECONDS).billing().bill_no());
            assertThatThrownBy(() -> service.create(input("{\"project_id\":2,\"stage_id\":1}", true)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ALREADY_BILLED"));
            assertThat(value("SELECT count(*)=count(DISTINCT bill_no) FROM bill.bill")).isEqualTo("t");
        } finally { pool.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void closingMissingOrExistingMonthWinsBeforeBillingCreate(boolean existingMonth) throws Exception {
        sql("DELETE FROM fin.mth_cls WHERE cls_ym='2026-10'");
        if (existingMonth) sql("INSERT INTO fin.mth_cls(cls_ym) VALUES('2026-10')");
        String before = snapshot();
        var pool = Executors.newSingleThreadExecutor();
        try (var closer = source.getConnection(); var statement = closer.createStatement()) {
            closer.setAutoCommit(false);
            statement.execute("LOCK TABLE fin.mth_cls IN SHARE ROW EXCLUSIVE MODE");
            var result = pool.submit(() -> authenticated(() -> {
                try { monthly(); return "CREATED"; }
                catch (ApiException ex) { return ex.code(); }
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (System.nanoTime() < deadline && !result.isDone()) {
                waiting = Boolean.TRUE.equals(new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE database=(SELECT oid FROM pg_database WHERE datname=current_database()) AND relation='fin.mth_cls'::regclass AND mode='ShareLock' AND NOT granted)", Boolean.class));
                if (waiting) break;
            }
            assertThat(waiting).as("Billing waits at the closing table guard, including an absent month").isTrue();
            statement.execute("SELECT prj_id FROM prj.prj WHERE prj_id=1 FOR UPDATE NOWAIT");
            statement.execute("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
            closer.commit();
            assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("CLOSED_MONTH");
            assertThat(snapshot()).isEqualTo(before);
        } finally { pool.shutdownNow(); }
    }
    @Test void resourceMaterializationUsesEffectivePartAndPreservesExplicitUnassignedHistory() throws Exception {
        long part = Long.parseLong(value("SELECT min(bsn_part_id) FROM mst.bsn_part WHERE use_yn='Y'"));
        sql("UPDATE prj.prj SET bsn_part_id=" + part + " WHERE prj_id=1");
        assertThat(mapper.resource(new ResourceKey(1, "2026-10")).partId()).isEqualTo(part);
        sql("INSERT INTO prj.prj_part_hist(prj_id,aply_bgn_ym,bsn_part_id) VALUES(1,'2026-10',NULL)");
        assertThat(mapper.resource(new ResourceKey(1, "2026-10")).partId()).isNull();
        assertThat(mapper.resource(new ResourceKey(1, "2026-09")).partId()).isEqualTo(part);
    }
    @Test void revokedRoleDeniesDirectReadAndUpdateWithoutDatabaseChanges() throws Exception {
        long id = monthly().billing().bill_id();
        sql("DELETE FROM sys.user_role WHERE user_id=" + actor);
        String before = snapshot();
        assertThatThrownBy(() -> service.get(id)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> service.update(id, input("{\"vat_amt\":17}", false)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void confirmedAdjustmentAndReceiptsUseRootAdjustedVatIncludedBalance() throws Exception {
        var row = monthly(); long id = row.billing().bill_id();
        sql("UPDATE bill.bill SET bill_stts_cd='CONFIRM' WHERE bill_id=" + id);
        sql("INSERT INTO bill.bill(bill_no,prj_id,atrb_ym,sply_amt,vat_amt,bill_stts_cd,amnd_yn,orgnl_bill_id) VALUES('B-2610-02',1,'2026-10',-100,-10,'CONFIRM','Y'," + id + ")");
        sql("INSERT INTO bill.rcpt(bill_id,rcpt_dt,rcpt_amt) VALUES(" + id + ",'2026-11-01',200)");
        var detail = service.get(id);
        assertThat(detail.billing().receivable_amt()).isEqualTo(790);
        assertThat(detail.receipts()).hasSize(1); assertThat(detail.amendments()).hasSize(1);
        assertThat(detail.billing().atrb_ym()).isEqualTo("2026-10");
    }
}
