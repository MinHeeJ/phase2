package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.AssignmentRules;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AssignmentHistoryMigrationTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;
    long project, developer, assignment, legacyChange;
    Flyway baseline;

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        jdbc = new JdbcTemplate(source);
        baseline = Flyway.configure().dataSource(source).defaultSchema("public")
            .locations("classpath:db/migration").target("3").load();
        baseline.migrate();
        fixtures = new AssignmentTestSupport(source);
        project = fixtures.createProject();
        developer = fixtures.createDeveloper();
        // A legacy physical row is needed before V4, not a replacement for the new create API.
        assignment = jdbc.queryForObject("SELECT nextval('asgn.seq_asgn_id')", Long.class);
        jdbc.update("""
            INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,created_at)
            VALUES(?,?,?,?, 'BE','2026-09-01','2026-12-31',TIMESTAMP '2026-10-14 12:00:00.123456')
            """, assignment, AssignmentRules.allocated(assignment), project, developer);
        legacyChange = jdbc.queryForObject("""
            INSERT INTO asgn.asgn_chg_hist(asgn_id,asgn_chg_ty_cd,aply_dt,aft_val)
            VALUES(?,'RATE','2026-10-01','7,000,000원') RETURNING asgn_chg_id
            """, Long.class, assignment);
    }

    @AfterEach void removeDatabase() throws Exception {
        if (fixtures != null) fixtures.close();
        if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }

    Flyway upgrade() {
        var flyway = Flyway.configure().dataSource(source).defaultSchema("public")
            .locations("classpath:db/migration").load();
        flyway.migrate();
        return flyway;
    }
    long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    LocalDateTime token() {
        return jdbc.queryForObject("SELECT coalesce(updated_at,created_at) FROM asgn.asgn WHERE asgn_id=?",
            LocalDateTime.class, assignment);
    }
    long append(String type) {
        return jdbc.queryForObject("""
            INSERT INTO asgn.asgn_chg_hist(asgn_id,asgn_chg_ty_cd,aply_dt,aft_val)
            VALUES(?,?,'2026-10-14','변경') RETURNING asgn_chg_id
            """, Long.class, assignment, type);
    }

    @Test void upgradePreservesBaselinesExistingIndexAndLegacyRows() {
        Map<String, Integer> checksums = new LinkedHashMap<>();
        for (var migration : baseline.info().applied()) {
            checksums.put(migration.getVersion().toString(), migration.getChecksum());
        }
        var index = jdbc.queryForObject("SELECT 'asgn.ix_asgn_chg_hist_asgn_id_aply_dt'::regclass::oid", Long.class);
        var flyway = upgrade();
        assertThat(flyway.info().applied()).hasSize(4);
        for (var migration : flyway.info().applied()) {
            if (checksums.containsKey(migration.getVersion().toString())) {
                assertThat(migration.getChecksum()).isEqualTo(checksums.get(migration.getVersion().toString()));
            }
        }
        var row = jdbc.queryForMap("SELECT * FROM asgn.asgn_chg_hist WHERE asgn_chg_id=?", legacyChange);
        assertThat(row).containsEntry("retro_yn", "N").containsEntry("fixed_yn", "N")
            .containsEntry("aft_val", "7,000,000원").containsEntry("rsn_cd", null)
            .containsEntry("fix_trgt_chg_id", null).containsEntry("chg_user_id", null);
        assertThat(row.get("chg_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT 'asgn.ix_asgn_chg_hist_asgn_id_aply_dt'::regclass::oid", Long.class))
            .isEqualTo(index);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test void exactSixColumnsHaveApprovedTypesNullabilityDefaultsAndSelfForeignKey() throws Exception {
        long originalColumns = count("SELECT count(*) FROM information_schema.columns WHERE table_schema='asgn' AND table_name='asgn_chg_hist'");
        upgrade();
        Map<String, String> types = Map.of("rsn_cd", "varchar:30:YES", "retro_yn", "bpchar:1:NO",
            "fix_trgt_chg_id", "int8:null:YES", "fixed_yn", "bpchar:1:NO",
            "chg_user_id", "int8:null:YES", "chg_at", "timestamp:null:NO");
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema='asgn' AND table_name='asgn_chg_hist'"))
            .isEqualTo(originalColumns + types.size());
        for (var entry : types.entrySet()) {
            var row = jdbc.queryForMap("""
                SELECT udt_name, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema='asgn' AND table_name='asgn_chg_hist' AND column_name=?
                """, entry.getKey());
            assertThat(row.get("udt_name") + ":" + row.get("character_maximum_length") + ":" + row.get("is_nullable"))
                .isEqualTo(entry.getValue());
            if (entry.getKey().endsWith("_yn")) assertThat(row.get("column_default").toString()).contains("'N'");
            if (entry.getKey().equals("chg_at")) assertThat(row.get("column_default").toString()).isEqualTo("CURRENT_TIMESTAMP");
        }
        try (var connection = source.getConnection();
             var keys = connection.getMetaData().getImportedKeys(null, "asgn", "asgn_chg_hist")) {
            boolean found = false;
            while (keys.next()) {
                if ("fk_asgn_chg_hist_fix_trgt".equals(keys.getString("FK_NAME"))) {
                    assertThat(keys.getString("FKCOLUMN_NAME")).isEqualTo("fix_trgt_chg_id");
                    assertThat(keys.getString("PKTABLE_SCHEM")).isEqualTo("asgn");
                    assertThat(keys.getString("PKTABLE_NAME")).isEqualTo("asgn_chg_hist");
                    assertThat(keys.getString("PKCOLUMN_NAME")).isEqualTo("asgn_chg_id");
                    found = true;
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test void onlyApprovedReasonGroupAndSixCodesAreSeededWithExistingScopeUnchanged() {
        long projects = count("SELECT count(*) FROM prj.prj");
        long developers = count("SELECT count(*) FROM mst.dev");
        upgrade();
        assertThat(jdbc.queryForMap("SELECT cd_grp_nm,cd_grp_se_cd,sort_seq,use_yn FROM cmn.cd_grp WHERE cd_grp_id='ASGN_CHG_RSN'"))
            .containsEntry("cd_grp_nm", "투입 변경 사유").containsEntry("cd_grp_se_cd", "BIZ")
            .containsEntry("sort_seq", 295).containsEntry("use_yn", "Y");
        assertThat(jdbc.queryForList("SELECT dtl_cd||':'||cd_nm||':'||sort_seq FROM cmn.cd_dtl WHERE cd_grp_id='ASGN_CHG_RSN' ORDER BY sort_seq", String.class))
            .containsExactly("CUST:고객 요청:1", "SCHED:프로젝트 일정 변경:2", "PRICE:단가 재협상:3",
                "PERSON:인력 사정:4", "FIXERR:입력 오류 바로잡기:5", "ETC:기타:9");
        assertThat(count("SELECT count(*) FROM cmn.cd_dtl WHERE cd_grp_id='ASGN_CHG_RSN' AND use_yn='Y' AND created_by IS NULL"))
            .isEqualTo(6);
        assertThat(count("SELECT count(*) FROM prj.prj")).isEqualTo(projects);
        assertThat(count("SELECT count(*) FROM mst.dev")).isEqualTo(developers);
        assertThat(count("SELECT count(*) FROM fin.mth_cls WHERE cls_stts_cd='CLOSED' AND cls_ym<='2026-09'"))
            .isEqualTo(9);
        assertThat(LocalDate.now(AssignmentTestSupport.CLOCK)).isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @ParameterizedTest @ValueSource(strings = {"START", "EXTEND", "EARLY_END", "RATE", "EXT_CHANCE", "PERIOD", "REPLACE", "FIX"})
    void allEightChangeKindsAreAcceptedByRealCheck(String type) { upgrade(); assertThat(append(type)).isPositive(); }

    @Test void unsupportedChangeKindIsRejectedByRealCheck() {
        upgrade();
        assertThatThrownBy(() -> append("OTHER")).hasRootCauseInstanceOf(SQLException.class);
    }
    @Test void retroFlagIsChecked() {
        upgrade();
        assertThatThrownBy(() -> jdbc.update("UPDATE asgn.asgn_chg_hist SET retro_yn='X' WHERE asgn_chg_id=?", legacyChange))
            .hasRootCauseInstanceOf(SQLException.class);
    }
    @Test void fixedFlagIsChecked() {
        upgrade();
        assertThatThrownBy(() -> jdbc.update("UPDATE asgn.asgn_chg_hist SET fixed_yn='X' WHERE asgn_chg_id=?", legacyChange))
            .hasRootCauseInstanceOf(SQLException.class);
    }
    @Test void nonexistentFixTargetIsRejectedBySelfForeignKey() {
        upgrade();
        assertThatThrownBy(() -> jdbc.update("UPDATE asgn.asgn_chg_hist SET fix_trgt_chg_id=? WHERE asgn_chg_id=?",
            Long.MAX_VALUE, legacyChange)).hasRootCauseInstanceOf(SQLException.class);
    }
    @Test void validFixCanReferenceExistingHistoryWithoutChangingItsBusinessText() {
        upgrade();
        long fix = append("FIX");
        jdbc.update("UPDATE asgn.asgn_chg_hist SET fix_trgt_chg_id=? WHERE asgn_chg_id=?", legacyChange, fix);
        jdbc.update("UPDATE asgn.asgn_chg_hist SET fixed_yn='Y' WHERE asgn_chg_id=?", legacyChange);
        assertThat(jdbc.queryForObject("SELECT aft_val FROM asgn.asgn_chg_hist WHERE asgn_chg_id=?", String.class, legacyChange))
            .isEqualTo("7,000,000원");
    }
    @Test void legacyNullTokenProjectsCreatedAtAndAdvanceIsStrictlyMonotonic() {
        upgrade();
        LocalDateTime expected = token();
        assertThat(expected).isEqualTo(LocalDateTime.parse("2026-10-14T12:00:00.123456"));
        LocalDateTime next = fixtures.transaction.execute(status -> {
            var locked = fixtures.concurrency.lockAndCompare(assignment, expected);
            return fixtures.concurrency.advance(locked);
        });
        assertThat(next).isAfter(expected);
        assertThat(token()).isEqualTo(next);
        assertThatThrownBy(() -> fixtures.transaction.execute(status -> {
            fixtures.concurrency.lockAndCompare(assignment, expected);
            return null;
        })).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("STALE_ASSIGNMENT"));
    }
    @Test void concurrentSameTokenSavesCommitExactlyOneHistory() throws Exception {
        upgrade();
        LocalDateTime expected = token();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<String> save = () -> {
            start.await();
            try {
                return fixtures.transaction.execute(status -> {
                    var locked = fixtures.concurrency.lockAndCompare(assignment, expected);
                    append("EXT_CHANCE");
                    fixtures.concurrency.advance(locked);
                    return "SAVED";
                });
            } catch (ApiException error) { return error.code(); }
        };
        try {
            var one = executor.submit(save);
            var two = executor.submit(save);
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("SAVED", "STALE_ASSIGNMENT");
            assertThat(count("SELECT count(*) FROM asgn.asgn_chg_hist WHERE asgn_chg_ty_cd='EXT_CHANCE'"))
                .isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void failureAfterParentAdvanceRollsBackTokenAndAllChildWrites() {
        upgrade();
        LocalDateTime before = token();
        assertThatThrownBy(() -> fixtures.transaction.execute(status -> {
            var locked = fixtures.concurrency.lockAndCompare(assignment, before);
            jdbc.update("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty,auto_mm_qty) VALUES(?,'2026-10',0.52,0.52)", assignment);
            jdbc.update("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(?,'2026-10-01',7000000)", assignment);
            append("START");
            fixtures.concurrency.advance(locked);
            append("OTHER");
            return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(token()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM asgn.asgn_mth")).isZero();
        assertThat(count("SELECT count(*) FROM asgn.untprc_hist")).isZero();
        assertThat(count("SELECT count(*) FROM asgn.asgn_chg_hist")).isEqualTo(1);
    }
    @Test void transactionPredatingLastSaveCannotMoveTokenBackwardsDespiteV1Trigger() {
        upgrade();
        long future = jdbc.queryForObject("""
            INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,created_at)
            VALUES('FUTURE-TOKEN',?,?,'BE','2027-01-01','2027-01-01',TIMESTAMP '2999-01-01') RETURNING asgn_id
            """, Long.class, project, developer);
        assertThatThrownBy(() -> fixtures.transaction.execute(status -> {
            fixtures.concurrency.lockAndCompare(future, LocalDateTime.of(2999, 1, 1, 0, 0));
            return null;
        })).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("STALE_ASSIGNMENT"));
    }
    @Test void sharedLockRequiresCallerTransaction() {
        upgrade();
        assertThatThrownBy(() -> fixtures.concurrency.lockAndCompare(assignment, token()))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void firstNumberExpectationBelongsOnlyToAnEmptyIsolatedAssignmentDatabase() {
        assertThat(assignment).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT asgn_no FROM asgn.asgn WHERE asgn_id=?", String.class, assignment))
            .isEqualTo("A-0001");
    }
    @Test void rolledBackSequenceValueIsNeverReusedAndLargeNumbersAreNotTruncated() {
        upgrade();
        long reserved = fixtures.transaction.execute(status -> {
            long id = jdbc.queryForObject("SELECT nextval('asgn.seq_asgn_id')", Long.class);
            status.setRollbackOnly();
            return id;
        });
        long next = jdbc.queryForObject("SELECT nextval('asgn.seq_asgn_id')", Long.class);
        assertThat(next).isGreaterThan(reserved);
        assertThat(AssignmentRules.allocated(next)).isNotEqualTo(AssignmentRules.allocated(reserved));
        jdbc.queryForObject("SELECT setval('asgn.seq_asgn_id',10000,false)", Long.class);
        long large = jdbc.queryForObject("SELECT nextval('asgn.seq_asgn_id')", Long.class);
        assertThat(AssignmentRules.allocated(large)).isEqualTo("A-10000");
    }
}
