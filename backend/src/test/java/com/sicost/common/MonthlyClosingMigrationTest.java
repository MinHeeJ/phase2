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

class MonthlyClosingMigrationTest {
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
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").target("6").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }

    Flyway latest() {
        return Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load();
    }

    long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    long adjustment(long amount, String reason) {
        return jdbc.queryForObject("""
            INSERT INTO cst.prvmn_adj(aply_ym,orgnl_ym,adj_se_cd,adj_amt,rsn_desc)
            VALUES('2026-10','2026-08','REVENUE',?,?) RETURNING adj_id
            """, Long.class, amount, reason);
    }
    void rejects(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DataIntegrityViolationException.class);
    }
    String results() {
        return jdbc.queryForObject("""
            SELECT jsonb_agg(jsonb_build_object('code',dtl_cd,'status','OK','count',0,'description',cd_nm)
              ORDER BY sort_seq)::text FROM cmn.cd_dtl WHERE cd_grp_id='CLOSE_CHECK'
            """, String.class);
    }

    @Test void upgradePreservesLegacyRowsChecksumsAndOnlyChangesClosingMobileFlag() {
        long old = adjustment(0, " ");
        var before = jdbc.queryForMap("SELECT * FROM cst.prvmn_adj WHERE adj_id=?", old);
        var history = jdbc.queryForList("SELECT version,checksum FROM public.flyway_schema_history ORDER BY installed_rank");
        var months = jdbc.queryForList("SELECT * FROM fin.mth_cls ORDER BY cls_ym");
        var menus = jdbc.queryForList("SELECT * FROM sys.menu WHERE scrn_id IS DISTINCT FROM 'UI-14' ORDER BY menu_id");
        assertThat(latest().migrate().migrationsExecuted).isEqualTo(1);
        var after = jdbc.queryForMap("SELECT * FROM cst.prvmn_adj WHERE adj_id=?", old);
        before.forEach((key, value) -> assertThat(after.get(key)).isEqualTo(value));
        assertThat(after.get("adj_stts_cd")).isEqualTo("ACTIVE");
        assertThat(after.get("orgnl_adj_id")).isNull();
        assertThat(after.get("cncl_user_id")).isNull();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM public.flyway_schema_history WHERE version<>'7' ORDER BY installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForList("SELECT * FROM fin.mth_cls ORDER BY cls_ym")).isEqualTo(months);
        assertThat(jdbc.queryForList("SELECT * FROM sys.menu WHERE scrn_id IS DISTINCT FROM 'UI-14' ORDER BY menu_id")).isEqualTo(menus);
        assertThat(jdbc.queryForObject("SELECT mbl_expsr_yn FROM sys.menu WHERE scrn_id='UI-14'", String.class)).isEqualTo("Y");
        latest().validate();
        assertThat(latest().migrate().migrationsExecuted).isZero();
    }

    @Test void cancellationPreservesOriginalAndRemovesOnlyActiveReference() {
        latest().migrate();
        long original = adjustment(100, "최초 조정");
        var before = jdbc.queryForMap("SELECT * FROM cst.prvmn_adj WHERE adj_id=?", original);
        jdbc.update("UPDATE cst.prvmn_adj SET adj_stts_cd='CANCELLED',cncl_rsn_desc='오입력',cncl_at=CURRENT_TIMESTAMP WHERE adj_id=?", original);
        var after = jdbc.queryForMap("SELECT * FROM cst.prvmn_adj WHERE adj_id=?", original);
        for (String field : List.of("adj_id","aply_ym","orgnl_ym","adj_se_cd","adj_amt","rsn_desc","created_at","created_by")) {
            assertThat(after.get(field)).isEqualTo(before.get(field));
        }
        assertThat(count("SELECT count(*) FROM cst.prvmn_adj WHERE orgnl_ym='2026-08' AND adj_stts_cd='ACTIVE'")).isZero();
        assertThat(count("SELECT count(*) FROM cst.prvmn_adj WHERE orgnl_ym='2026-08'")).isEqualTo(1);
        long replacement = adjustment(70, "재등록");
        jdbc.update("UPDATE cst.prvmn_adj SET orgnl_adj_id=? WHERE adj_id=?", original, replacement);
        assertThat(count("SELECT sum(adj_amt) FROM cst.prvmn_adj WHERE adj_stts_cd='ACTIVE'")).isEqualTo(70);
        rejects(() -> jdbc.update("DELETE FROM cst.prvmn_adj WHERE adj_id=?", original));
    }

    @ParameterizedTest @ValueSource(strings = {"status", "zero", "reason", "apply-month", "original-month", "cancel-reason", "cancel-time", "active-metadata", "missing-original", "self-original"})
    void invalidNewAdjustmentWritesAreRejected(String rule) {
        latest().migrate();
        long id = adjustment(1, "검증 조정");
        switch (rule) {
            case "status" -> rejects(() -> jdbc.update("UPDATE cst.prvmn_adj SET adj_stts_cd='DELETED' WHERE adj_id=?", id));
            case "zero" -> rejects(() -> adjustment(0, "금액 없음"));
            case "reason" -> rejects(() -> adjustment(1, " "));
            case "apply-month" -> rejects(() -> jdbc.update("UPDATE cst.prvmn_adj SET aply_ym='2026-13' WHERE adj_id=?", id));
            case "original-month" -> rejects(() -> jdbc.update("UPDATE cst.prvmn_adj SET orgnl_ym='0000-01' WHERE adj_id=?", id));
            case "cancel-reason" -> rejects(() -> jdbc.update("UPDATE cst.prvmn_adj SET adj_stts_cd='CANCELLED',cncl_at=CURRENT_TIMESTAMP WHERE adj_id=?", id));
            case "cancel-time" -> rejects(() -> jdbc.update("UPDATE cst.prvmn_adj SET adj_stts_cd='CANCELLED',cncl_rsn_desc='취소' WHERE adj_id=?", id));
            case "active-metadata" -> rejects(() -> jdbc.update("UPDATE cst.prvmn_adj SET cncl_at=CURRENT_TIMESTAMP WHERE adj_id=?", id));
            case "missing-original" -> rejects(() -> jdbc.update("UPDATE cst.prvmn_adj SET orgnl_adj_id=? WHERE adj_id=?", Long.MAX_VALUE, id));
            default -> rejects(() -> jdbc.update("UPDATE cst.prvmn_adj SET orgnl_adj_id=adj_id WHERE adj_id=?", id));
        }
    }

    @Test void checkRunsAppendIndependentlyWithNullableActorAndPreservedNineResults() {
        latest().migrate();
        String results = results();
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO fin.mth_cls_chk_hist(cls_ym,chk_rslt_json) VALUES('2026-10',?::jsonb)", results);
        }
        assertThat(count("SELECT count(*) FROM fin.mth_cls_chk_hist WHERE chk_user_id IS NULL")).isEqualTo(2);
        assertThat(count("SELECT count(DISTINCT chk_run_id) FROM fin.mth_cls_chk_hist")).isEqualTo(2);
        assertThat(count("SELECT min(jsonb_array_length(chk_rslt_json)) FROM fin.mth_cls_chk_hist")).isEqualTo(9);
        assertThat(count("SELECT count(*) FROM fin.mth_cls_chk")).isZero();
        rejects(() -> jdbc.update("UPDATE fin.mth_cls_chk_hist SET chk_at=CURRENT_TIMESTAMP"));
        rejects(() -> jdbc.update("DELETE FROM fin.mth_cls_chk_hist"));
    }

    @Test void checkArchiveRequiresParentAndCompleteArray() {
        latest().migrate();
        rejects(() -> jdbc.update("INSERT INTO fin.mth_cls_chk_hist(cls_ym,chk_rslt_json) VALUES('2099-01',?::jsonb)", results()));
        rejects(() -> jdbc.update("INSERT INTO fin.mth_cls_chk_hist(cls_ym,chk_rslt_json) VALUES('2026-10','[]')"));
        rejects(() -> jdbc.update("INSERT INTO fin.mth_cls_chk_hist(cls_ym,chk_rslt_json) VALUES('2026-10','{}')"));
    }

    @Test void archiveAndLatestCheckRollbackTogether() {
        latest().migrate();
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        assertThatThrownBy(() -> tx.execute(status -> {
            jdbc.update("INSERT INTO fin.mth_cls_chk(cls_ym,cls_chk_cd,chk_rslt_cd) VALUES('2026-10','SALARY_UPLOAD','OK')");
            jdbc.update("INSERT INTO fin.mth_cls_chk_hist(cls_ym,chk_rslt_json) VALUES('2026-10',?::jsonb)", results());
            throw new IllegalStateException("점검 저장 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT count(*) FROM fin.mth_cls_chk")).isZero();
        assertThat(count("SELECT count(*) FROM fin.mth_cls_chk_hist")).isZero();
    }

    @Test void projectOnlyExpenseHasNoInventedDeveloperAndProfitUsesGeneratedColumns() {
        latest().migrate();
        long project = jdbc.queryForObject("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd) VALUES('P-26999','경비 검증','MO') RETURNING prj_id", Long.class);
        jdbc.update("INSERT INTO cst.cst_aloc(cst_ym,prj_id,aloc_ty_cd,drct_expns_amt) VALUES('2026-10',?,'PROJECT',40)", project);
        assertThat(count("SELECT count(*) FROM cst.cst_aloc WHERE dev_id IS NULL AND drct_expns_amt=40")).isEqualTo(1);
        jdbc.update("INSERT INTO fin.mth_pl(pl_ym,pl_src_cd,sls_amt,aloc_cst_amt,drct_expns_amt) VALUES('2026-10','CLOSE',100,20,40)");
        assertThat(count("SELECT grs_prft_amt FROM fin.mth_pl WHERE pl_ym='2026-10'")).isEqualTo(40);
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema='fin' AND table_name='mth_pl' AND is_generated='ALWAYS'")).isEqualTo(5);
    }
}
