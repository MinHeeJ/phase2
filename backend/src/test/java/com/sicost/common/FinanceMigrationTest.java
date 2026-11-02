package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.persistence.SchemaValidator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FinanceMigrationTest {
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
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").target("5").load().migrate();
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
    long payment(String state, String date, long amount) {
        return jdbc.queryForObject("""
            INSERT INTO fin.pay(pay_ym,pay_se_cd,rcvr_se_cd,rcvr_nm,pay_amt,pay_plan_dt,pay_stts_cd,pay_dt)
            VALUES('2026-10','ETC','ETC','지급 대상',?,DATE '2026-10-20',?,CAST(? AS date)) RETURNING pay_id
            """, Long.class, amount, state, date);
    }
    long execution(long payId, long amount, Long original, String reason) {
        return jdbc.queryForObject("""
            INSERT INTO fin.pay_execution(pay_id,pay_dt,pay_amt,reversal_of_id,rsn_desc)
            VALUES(?,DATE '2026-10-20',?,?,?) RETURNING pay_execution_id
            """, Long.class, payId, amount, original, reason);
    }
    long entry(String month, Long original) {
        return jdbc.queryForObject("""
            INSERT INTO fin.entry(entry_ym,fin_se_cd,item_nm,entry_amt,copy_entry_id)
            VALUES(?,'SGA','재무 항목',100,?) RETURNING entry_id
            """, Long.class, month, original);
    }
    void rejects(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void upgradeBackfillsActualRowsExactlyOnceAndKeepsParentAndCloseState() throws Exception {
        long paid = payment("PAID", "2026-10-20", 1000000);
        payment("PLAN", null, 600000);
        payment("UNPAID", null, 300000);
        payment("PAID", null, 200000);
        var parent = jdbc.queryForMap("SELECT * FROM fin.pay WHERE pay_id=?", paid);
        var closed = jdbc.queryForList("SELECT * FROM fin.mth_cls ORDER BY cls_ym");
        var flyway = latest();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        new SchemaValidator(source, new ObjectMapper()).validate();
        var actual = jdbc.queryForMap("SELECT * FROM fin.pay_execution WHERE pay_id=?", paid);
        assertThat(actual.get("pay_dt")).isEqualTo(parent.get("pay_dt"));
        assertThat(actual.get("pay_amt")).isEqualTo(parent.get("pay_amt"));
        assertThat(actual.get("created_at")).isEqualTo(parent.get("created_at"));
        assertThat(actual.get("created_by")).isNull();
        assertThat(count("SELECT count(*) FROM fin.pay_execution")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM fin.pay WHERE pay_id=?", paid)).isEqualTo(parent);
        assertThat(jdbc.queryForList("SELECT * FROM fin.mth_cls ORDER BY cls_ym")).isEqualTo(closed);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(count("SELECT count(*) FROM fin.pay_execution")).isEqualTo(1);
    }
    @Test void softDeletePreservesReferencesAndDuplicateCopyHistory() {
        latest().migrate();
        long original = entry("2026-09", null);
        long copy = entry("2026-10", original);
        jdbc.update("UPDATE fin.entry SET deleted_yn='Y' WHERE entry_id IN (?,?)", original, copy);
        assertThat(count("SELECT count(*) FROM fin.entry WHERE deleted_yn='Y'")).isEqualTo(2);
        rejects(() -> entry("2026-10", original));
        assertThat(entry("2026-11", original)).isPositive();
        rejects(() -> jdbc.update("DELETE FROM fin.entry WHERE entry_id=?", original));
        rejects(() -> jdbc.update("UPDATE fin.entry SET deleted_yn='X' WHERE entry_id=?", copy));
    }
    @Test void sourceUniquenessDistinguishesTypeAndMonthButNeverBindsManualOrCarry() {
        latest().migrate();
        long root = payment("PLAN", null, 1000000);
        jdbc.update("UPDATE fin.pay SET pay_src_cd='AUTO_FIN',src_rec_id=7 WHERE pay_id=?", root);
        long duplicate = payment("PLAN", null, 1000000);
        rejects(() -> jdbc.update("UPDATE fin.pay SET pay_src_cd='AUTO_FIN',src_rec_id=7 WHERE pay_id=?", duplicate));
        jdbc.update("UPDATE fin.pay SET pay_src_cd='AUTO_FIN',src_rec_id=7,pay_se_cd='CORP_TAX' WHERE pay_id=?", duplicate);
        long otherMonth = payment("PLAN", null, 1000000);
        jdbc.update("UPDATE fin.pay SET pay_src_cd='AUTO_FIN',src_rec_id=7,pay_ym='2026-11' WHERE pay_id=?", otherMonth);
        long manualOne = payment("PLAN", null, 10);
        long manualTwo = payment("PLAN", null, 10);
        jdbc.update("UPDATE fin.pay SET src_rec_id=7 WHERE pay_id IN (?,?)", manualOne, manualTwo);
        long child = payment("PLAN", null, 600000);
        jdbc.update("UPDATE fin.pay SET pay_src_cd='AUTO_FIN',src_rec_id=7,cry_pay_id=? WHERE pay_id=?", root, child);
    }
    @Test void activeCarryIsUniqueUntilCancelledIncludingPaidChildren() {
        latest().migrate();
        long root = payment("UNPAID", null, 1000000);
        long child = payment("PAID", "2026-10-20", 600000);
        long replacement = payment("PLAN", null, 600000);
        jdbc.update("UPDATE fin.pay SET cry_pay_id=? WHERE pay_id=?", root, child);
        rejects(() -> jdbc.update("UPDATE fin.pay SET cry_pay_id=? WHERE pay_id=?", root, replacement));
        jdbc.update("UPDATE fin.pay SET pay_stts_cd='CANCEL' WHERE pay_id=?", child);
        jdbc.update("UPDATE fin.pay SET cry_pay_id=? WHERE pay_id=?", root, replacement);
    }
    @Test void openingBalanceIsGloballyUniqueAcrossYearsWithoutThresholdDefaults() {
        latest().migrate();
        jdbc.update("INSERT INTO fin.cash_bal(bal_ym,bgn_bal_amt,init_yn) VALUES('2026-10',0,'Y')");
        rejects(() -> jdbc.update("INSERT INTO fin.cash_bal(bal_ym,bgn_bal_amt,init_yn) VALUES('2027-01',0,'Y')"));
        jdbc.update("INSERT INTO fin.cash_bal(bal_ym) VALUES('2026-11'),('2026-12')");
        assertThat(count("SELECT count(*) FROM fin.cash_bal WHERE min_oprt_fund_amt IS NOT NULL")).isZero();
    }
    @Test void partialExecutionAndReversalPreservePlanAndCancelCashOnce() {
        latest().migrate();
        long root = payment("PLAN", null, 1000000);
        long original = execution(root, 400000, null, null);
        assertThat(count("SELECT sum(pay_amt) FROM fin.pay_execution")).isEqualTo(400000);
        assertThat(count("SELECT pay_amt FROM fin.pay")).isEqualTo(1000000);
        execution(root, -400000, original, "지급 취소");
        assertThat(count("SELECT sum(pay_amt) FROM fin.pay_execution")).isZero();
        rejects(() -> execution(root, -400000, original, "중복 취소"));
    }
    @ParameterizedTest @ValueSource(strings={"amount", "payment", "reason", "nested", "missing", "negative"})
    void invalidReversalsAndExecutionsAreRejected(String rule) {
        latest().migrate();
        long root = payment("PLAN", null, 1000000);
        long original = execution(root, 400000, null, null);
        switch (rule) {
            case "amount" -> rejects(() -> execution(root, -399999, original, "금액 불일치"));
            case "payment" -> {
                long other = payment("PLAN", null, 1000000);
                rejects(() -> execution(other, -400000, original, "다른 지급"));
            }
            case "reason" -> rejects(() -> execution(root, -400000, original, " "));
            case "nested" -> {
                long reversed = execution(root, -400000, original, "취소");
                rejects(() -> execution(root, 400000, reversed, "반대 실행 재취소"));
            }
            case "missing" -> rejects(() -> execution(root, -400000, Long.MAX_VALUE, "없는 원본"));
            default -> rejects(() -> execution(root, -1, null, null));
        }
    }
    @ParameterizedTest @ValueSource(strings={"UPDATE", "DELETE"})
    void executionsAreImmutable(String command) {
        latest().migrate();
        long root = payment("PLAN", null, 1000000);
        long id = execution(root, 400000, null, null);
        rejects(() -> jdbc.update(command.equals("UPDATE")
            ? "UPDATE fin.pay_execution SET pay_amt=1 WHERE pay_execution_id=?"
            : "DELETE FROM fin.pay_execution WHERE pay_execution_id=?", id));
        assertThat(count("SELECT sum(pay_amt) FROM fin.pay_execution")).isEqualTo(400000);
    }
    @ParameterizedTest @ValueSource(strings={"entry", "source", "carry", "opening"})
    void legacyDuplicatesFailWithoutDiscardingHistory(String resource) {
        switch (resource) {
            case "entry" -> {
                long original = entry("2026-09", null);
                entry("2026-10", original); entry("2026-10", original);
            }
            case "source" -> {
                payment("PLAN", null, 1); payment("PLAN", null, 2);
                jdbc.update("UPDATE fin.pay SET pay_src_cd='AUTO_FIN',src_rec_id=7");
            }
            case "carry" -> {
                long root = payment("UNPAID", null, 1000000);
                long first = payment("PLAN", null, 600000);
                long second = payment("PLAN", null, 600000);
                jdbc.update("UPDATE fin.pay SET cry_pay_id=? WHERE pay_id IN (?,?)", root, first, second);
            }
            default -> jdbc.update("INSERT INTO fin.cash_bal(bal_ym,init_yn) VALUES('2026-10','Y'),('2027-01','Y')");
        }
        var before = jdbc.queryForList("SELECT * FROM fin.entry ORDER BY entry_id");
        var payments = jdbc.queryForList("SELECT * FROM fin.pay ORDER BY pay_id");
        var balances = jdbc.queryForList("SELECT * FROM fin.cash_bal ORDER BY bal_ym");
        assertThatThrownBy(() -> latest().migrate()).hasStackTraceContaining("before migration");
        assertThat(jdbc.queryForList("SELECT * FROM fin.entry ORDER BY entry_id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM fin.pay ORDER BY pay_id")).isEqualTo(payments);
        assertThat(jdbc.queryForList("SELECT * FROM fin.cash_bal ORDER BY bal_ym")).isEqualTo(balances);
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema='fin' AND table_name='pay_execution'")).isZero();
    }
    @Test void databaseConstraintsSerializeCompetingOpeningWrites() throws Exception {
        latest().migrate();
        try (var first = source.getConnection(); var second = source.getConnection()) {
            first.setAutoCommit(false); second.setAutoCommit(false);
            try (var a = first.createStatement(); var b = second.createStatement()) {
                a.executeUpdate("INSERT INTO fin.cash_bal(bal_ym,init_yn) VALUES('2026-10','Y')");
                b.execute("SET LOCAL lock_timeout='100ms'");
                assertThatThrownBy(() -> b.executeUpdate("INSERT INTO fin.cash_bal(bal_ym,init_yn) VALUES('2027-01','Y')"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55P03"));
                second.rollback(); first.commit();
            }
        }
        assertThat(count("SELECT count(*) FROM fin.cash_bal WHERE init_yn='Y'")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"column", "foreign-key", "unique"})
    void startupValidatorDetectsFinanceSchemaDrift(String drift) {
        latest().migrate();
        switch (drift) {
            case "column" -> jdbc.execute("ALTER TABLE fin.entry ALTER COLUMN deleted_yn DROP NOT NULL");
            case "foreign-key" -> jdbc.execute("ALTER TABLE fin.pay_execution DROP CONSTRAINT fk_pay_execution_pay");
            default -> jdbc.execute("DROP INDEX fin.uk_pay_execution_reversal");
        }
        assertThatThrownBy(() -> new SchemaValidator(source, new ObjectMapper()).validate())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("fin.");
    }
}
