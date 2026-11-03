package com.sicost.common;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkforcePlanningSchemaTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").target("7").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }

    Flyway latest() {
        return Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").target("8").load();
    }
    long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    long opportunity() {
        long customer = jdbc.queryForObject("""
            INSERT INTO mst.ptnr(ptnr_no,ptnr_nm,ptnr_se_cd,biz_reg_no)
            VALUES(?, '계약 검증 고객', 'CUST', '123-45-67890') RETURNING ptnr_id
            """, Long.class, "C-" + UUID.randomUUID().toString().substring(0, 12));
        return jdbc.queryForObject("""
            INSERT INTO bsn.oppt(oppt_no,oppt_nm,cust_ptnr_id)
            VALUES(?, '계약 검증 기회', ?) RETURNING oppt_id
            """, Long.class, "O-" + UUID.randomUUID().toString().substring(0, 12), customer);
    }
    long need(long opportunity) {
        return jdbc.queryForObject("""
            INSERT INTO bsn.oppt_need(oppt_id,job_cd,bgn_ym,mnth_qty,psn_cnt)
            VALUES(?, 'BE', '2026-11', 1, 999) RETURNING need_id
            """, Long.class, opportunity);
    }
    long project(Long opportunity) {
        return jdbc.queryForObject("""
            INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,oppt_id)
            VALUES(?, '계약 검증 프로젝트', 'MO', ?) RETURNING prj_id
            """, Long.class, "P-" + UUID.randomUUID().toString().substring(0, 12), opportunity);
    }
    void plan(long project, Long need, String grade, String month) {
        jdbc.update("""
            INSERT INTO prj.psn_plan(prj_id,plan_ym,job_cd,grd_cd,need_psn_cnt,need_mm_qty,src_need_id)
            VALUES(?,?,'BE',?,999.00,999.00,?)
            """, project, month, grade, need);
    }
    void rejects(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void upgradePreservesExistingValuesChecksumsMenusAndClosedSources() {
        long opportunity = opportunity();
        long need = need(opportunity);
        var before = jdbc.queryForMap("SELECT * FROM bsn.oppt WHERE oppt_id=?", opportunity);
        var parameters = jdbc.queryForList("SELECT * FROM cmn.oprt_std ORDER BY oprt_std_id,aply_bgn_ym");
        var history = jdbc.queryForList("SELECT version,checksum FROM public.flyway_schema_history ORDER BY installed_rank");
        var menus = jdbc.queryForList("SELECT * FROM sys.menu ORDER BY menu_id");
        var closed = WorkforcePlanningAssertions.captureClosedSources(jdbc);
        assertThat(latest().migrate().migrationsExecuted).isEqualTo(1);
        var after = jdbc.queryForMap("SELECT * FROM bsn.oppt WHERE oppt_id=?", opportunity);
        before.forEach((key, value) -> assertThat(after.get(key)).isEqualTo(value));
        assertThat(after.get("zero_cost_evidence")).isNull();
        assertThat(after.get("zero_cost_validation")).isNull();
        assertThat(after.get("amount_difference_reason")).isNull();
        assertThat(jdbc.queryForObject("SELECT use_yn FROM bsn.oppt_need WHERE need_id=?", String.class, need)).isEqualTo("Y");
        for (var row : parameters) {
            assertThat(jdbc.queryForMap("SELECT * FROM cmn.oprt_std WHERE oprt_std_id=? AND aply_bgn_ym=?",
                row.get("oprt_std_id"), row.get("aply_bgn_ym"))).isEqualTo(row);
        }
        assertThat(jdbc.queryForList("SELECT version,checksum FROM public.flyway_schema_history WHERE version<>'8' ORDER BY installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForList("SELECT * FROM sys.menu ORDER BY menu_id")).isEqualTo(menus);
        closed.assertUnchanged(jdbc);
        latest().validate();
        assertThat(latest().migrate().migrationsExecuted).isZero();
    }

    @Test void multipleSourceNeedsAndManualBucketCoexistWithoutLosingPrecision() {
        latest().migrate();
        long opportunity = opportunity(), project = project(opportunity);
        long first = need(opportunity), second = need(opportunity);
        plan(project, first, "", "2026-11");
        plan(project, second, "", "2026-11");
        plan(project, null, "", "2026-11");
        assertThat(count("SELECT count(*) FROM prj.psn_plan")).isEqualTo(3);
        assertThat(count("SELECT max(need_psn_cnt) FROM prj.psn_plan")).isEqualTo(999);
        assertThat(count("SELECT max(need_mm_qty) FROM prj.psn_plan")).isEqualTo(999);
        assertThat(jdbc.queryForList("SELECT numeric_precision,numeric_scale FROM information_schema.columns WHERE table_schema='prj' AND table_name='psn_plan' AND column_name IN ('need_psn_cnt','need_mm_qty') ORDER BY column_name"))
            .containsExactly(Map.of("numeric_precision",6,"numeric_scale",2), Map.of("numeric_precision",5,"numeric_scale",2));
    }

    @Test void duplicateManualBucketIsRejected() {
        latest().migrate();
        long project = project(null);
        plan(project, null, "", "2026-11");
        rejects(() -> plan(project, null, "", "2026-11"));
        plan(project, null, "SENIOR", "2026-11");
    }

    @Test void duplicateSourceNeedMonthIsRejectedEvenWhenGradeChanges() {
        latest().migrate();
        long opportunity = opportunity(), project = project(opportunity), need = need(opportunity);
        plan(project, need, "", "2026-11");
        rejects(() -> plan(project, need, "SENIOR", "2026-11"));
        plan(project, need, "", "2026-12");
    }

    @Test void oneOpportunityCannotProduceTwoProjectsButManualProjectsRemainIndependent() {
        latest().migrate();
        long opportunity = opportunity();
        project(opportunity);
        rejects(() -> project(opportunity));
        project(null);
        project(null);
        assertThat(count("SELECT count(*) FROM prj.prj")).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "RELEASED", "CONVERTED"})
    void referencedNeedCannotDeleteTemporaryAssignmentHistory(String state) {
        latest().migrate();
        long opportunity = opportunity(), need = need(opportunity);
        long dev = jdbc.queryForObject("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd) VALUES(?,'검증 인력','FL') RETURNING dev_id",
            Long.class, "K-" + UUID.randomUUID().toString().substring(0, 12));
        long temporary = jdbc.queryForObject("""
            INSERT INTO asgn.tmpr_asgn(need_id,dev_id,bgn_ym,end_ym,tmpr_asgn_stts_cd)
            VALUES(?,?,'2026-11','2026-12',?) RETURNING tmpr_asgn_id
            """, Long.class, need, dev, state);
        rejects(() -> jdbc.update("DELETE FROM bsn.oppt_need WHERE need_id=?", need));
        jdbc.update("UPDATE bsn.oppt_need SET use_yn='N' WHERE need_id=?", need);
        assertThat(jdbc.queryForObject("SELECT need_id FROM asgn.tmpr_asgn WHERE tmpr_asgn_id=?", Long.class, temporary)).isEqualTo(need);
        assertThat(jdbc.queryForObject("SELECT tmpr_asgn_stts_cd FROM asgn.tmpr_asgn WHERE tmpr_asgn_id=?", String.class, temporary)).isEqualTo(state);
    }

    @Test void inactiveNeedStateRejectsUnknownValues() {
        latest().migrate();
        long need = need(opportunity());
        rejects(() -> jdbc.update("UPDATE bsn.oppt_need SET use_yn='X' WHERE need_id=?", need));
    }

    @Test void missingCriteriaAreSeededButAnyExistingVersionPreventsDefaultInsertion() {
        jdbc.update("INSERT INTO cmn.oprt_std(oprt_std_id,aply_bgn_ym,oprt_std_nm,num_val) VALUES('QUOTE_TARGET_MARGIN','2026-11','기존 기준',17)");
        latest().migrate();
        assertThat(count("SELECT count(*) FROM cmn.oprt_std WHERE oprt_std_id='QUOTE_TARGET_MARGIN'")).isEqualTo(1);
        assertThat(count("SELECT num_val FROM cmn.oprt_std WHERE oprt_std_id='QUOTE_TARGET_MARGIN'")).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT num_val FROM cmn.oprt_std WHERE oprt_std_id='QUOTE_APPROVAL_RATE' AND aply_bgn_ym='2000-01'", java.math.BigDecimal.class)).isEqualByComparingTo("15");
        assertThat(jdbc.queryForObject("SELECT num_val FROM cmn.oprt_std WHERE oprt_std_id='QUOTE_APPROVAL_TOLERANCE'", java.math.BigDecimal.class)).isEqualByComparingTo("0.50");
    }

    @Test void snapshotAssertionDetectsClosedSourceChangesWithoutLoggingSensitiveRows() {
        latest().migrate();
        jdbc.update("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2099-01','CLOSED')");
        var before = WorkforcePlanningAssertions.captureClosedSources(jdbc);
        before.assertUnchanged(jdbc);
        jdbc.update("UPDATE fin.mth_cls SET cls_stts_cd='OPEN' WHERE cls_ym='2099-01'");
        assertThatThrownBy(() -> before.assertUnchanged(jdbc)).isInstanceOf(AssertionError.class)
            .hasMessageContaining("Closed source changed: fin.mth_cls");
    }
}
