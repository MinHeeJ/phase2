package com.sicost.common;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AiMigrationTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
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
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").target("8").load().migrate();
        jdbc = new JdbcTemplate(source);
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }
    Flyway latest() {
        return Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").target("9").load();
    }
    long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    void rejects(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(DataIntegrityViolationException.class); }
    long request(String type) {
        return jdbc.queryForObject("INSERT INTO ai.rcmd_rqst(rqst_ty_cd,cond,scr_wght) VALUES(?, '{}'::jsonb,'{}'::jsonb) RETURNING rcmd_rqst_id", Long.class, type);
    }
    long developer() {
        return jdbc.queryForObject("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd) VALUES(?,'시험 인력','FT') RETURNING dev_id", Long.class, "K-" + UUID.randomUUID().toString().substring(0,12));
    }
    long project() {
        return jdbc.queryForObject("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd) VALUES(?,'시험 프로젝트','IN') RETURNING prj_id", Long.class, "P-" + UUID.randomUUID().toString().substring(0,12));
    }
    long plan(long project, String month) {
        return jdbc.queryForObject("INSERT INTO prj.psn_plan(prj_id,plan_ym,job_cd,need_mm_qty) VALUES(?,?,'BE',1) RETURNING psn_plan_id", Long.class, project, month);
    }
    long result(long request, long dev, long project, long plan, int rank) {
        return jdbc.queryForObject("""
            INSERT INTO ai.rcmd_next_rslt(rqst_id,dev_id,prj_id,psn_plan_id,rank_seq,
              job_scr,skl_scr,cust_scr,grd_scr,achv_scr,exp_scr,asgn_psbl_scr,tot_scr)
            VALUES(?,?,?,?,?,25,20,15,10,10,10,10,100) RETURNING rcmd_next_rslt_id
            """, Long.class, request, dev, project, plan, rank);
    }
    @Test void upgradePreservesLegacyRequestScoresDuplicateForecastsUsersAndChecksums() {
        long request = request("LEGACY"), dev = developer();
        jdbc.update("INSERT INTO ai.rcmd_rslt(rcmd_rqst_id,dev_id,rank_no,tot_scr) VALUES(?,?,1,37.25)", request, dev);
        jdbc.update("INSERT INTO ai.frcst_exec(base_ym,frcst_ty_cd) VALUES('2026-10','REVENUE'),('2026-10','REVENUE')");
        jdbc.update("INSERT INTO ai.frcst_rslt(frcst_exec_id,obj_ym,mid_val) SELECT frcst_exec_id,'2026-11',123 FROM ai.frcst_exec");
        var history = jdbc.queryForList("SELECT version,checksum FROM public.flyway_schema_history ORDER BY installed_rank");
        var original = jdbc.queryForMap("SELECT * FROM ai.rcmd_rqst WHERE rcmd_rqst_id=?", request);
        var scores = jdbc.queryForList("SELECT * FROM ai.rcmd_rslt");
        var results = jdbc.queryForList("SELECT * FROM ai.frcst_rslt");
        long users = count("SELECT count(*) FROM sys.user_info");
        assertThat(latest().migrate().migrationsExecuted).isEqualTo(1);
        var after = jdbc.queryForMap("SELECT * FROM ai.rcmd_rqst WHERE rcmd_rqst_id=?", request);
        original.forEach((key,value) -> assertThat(after.get(key)).isEqualTo(value));
        assertThat(after.get("session_key_hash")).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM ai.rcmd_rslt")).isEqualTo(scores);
        assertThat(jdbc.queryForList("SELECT * FROM ai.frcst_rslt")).isEqualTo(results);
        assertThat(count("SELECT count(*) FROM ai.frcst_exec WHERE official_yn='N'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM sys.user_info")).isEqualTo(users);
        assertThat(jdbc.queryForList("SELECT version,checksum FROM public.flyway_schema_history WHERE version<>'9' ORDER BY installed_rank")).isEqualTo(history);
        rejects(() -> jdbc.update("INSERT INTO ai.qry_hist(user_id,qstn_desc) VALUES(NULL,'정제 요약')"));
        latest().validate(); assertThat(latest().migrate().migrationsExecuted).isZero();
    }
    @Test void slotsOnSameProjectRemainDistinctButSameSlotCannotRepeatWithinRequest() {
        latest().migrate();
        long request = request("NEXT"), dev = developer(), project = project();
        long first = plan(project, "2027-01"), second = plan(project, "2027-02");
        result(request, dev, project, first, 1); result(request, dev, project, second, 2);
        rejects(() -> result(request, dev, project, first, 3));
        assertThat(count("SELECT count(*) FROM ai.rcmd_next_rslt")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ai.rcmd_rslt")).isZero();
        result(request("NEXT"), dev, project, first, 1);
    }
    @Test void missingAndAmbiguousSlotIdentityAndInvalidScoreAreRejected() {
        latest().migrate();
        long project = project();
        long result = result(request("NEXT"), developer(), project, plan(project, "2027-01"), 1);
        rejects(() -> jdbc.update("UPDATE ai.rcmd_next_rslt SET psn_plan_id=NULL WHERE rcmd_next_rslt_id=?", result));
        rejects(() -> jdbc.update("UPDATE ai.rcmd_next_rslt SET tot_scr=99 WHERE rcmd_next_rslt_id=?", result));
        rejects(() -> jdbc.update("UPDATE ai.rcmd_next_rslt SET decision_cd='REJECT',reject_rsn_desc=' ' WHERE rcmd_next_rslt_id=?", result));
        rejects(() -> jdbc.update("UPDATE ai.rcmd_next_rslt SET rank_seq=11 WHERE rcmd_next_rslt_id=?", result));
    }
    @Test void officialUniquenessAppliesOnlyToNewOfficialRowsAndUnavailableValuesStayNull() {
        latest().migrate();
        jdbc.update("INSERT INTO ai.frcst_exec(base_ym,frcst_ty_cd) VALUES('2026-10','REVENUE'),('2026-10','REVENUE')");
        long execution = jdbc.queryForObject("INSERT INTO ai.frcst_exec(base_ym,frcst_ty_cd,official_yn) VALUES('2026-10','REVENUE','Y') RETURNING frcst_exec_id", Long.class);
        rejects(() -> jdbc.update("INSERT INTO ai.frcst_exec(base_ym,frcst_ty_cd,official_yn) VALUES('2026-10','REVENUE','Y')"));
        jdbc.update("INSERT INTO ai.frcst_rslt(frcst_exec_id,obj_ym) VALUES(?,'2026-11')", execution);
        assertThat(jdbc.queryForObject("SELECT mid_val FROM ai.frcst_rslt WHERE frcst_exec_id=?", java.math.BigDecimal.class, execution)).isNull();
        jdbc.update("INSERT INTO ai.frcst_exec(base_ym,frcst_ty_cd,official_yn) VALUES('2026-10','OP_PROFIT','Y')");
    }
    long conversation() {
        return jdbc.queryForObject("INSERT INTO ai.chat_session(session_key_hash) VALUES(?) RETURNING session_id", Long.class, "a".repeat(64));
    }
    long message(long session, Long context) {
        return jdbc.queryForObject("INSERT INTO ai.chat_msg(session_id,context_msg_id,question_summary,answer_summary,status_cd) VALUES(?,?,'정제 질문','정제 답','OK') RETURNING msg_id", Long.class, session, context);
    }
    @Test void chatRequiresSameSessionContextAndHasIndependentRetention() {
        latest().migrate();
        long first = conversation(), second = conversation(), msg = message(first, null);
        message(first, msg);
        rejects(() -> message(second, msg));
        var before = jdbc.queryForMap("SELECT retention_until,expires_at FROM ai.chat_session WHERE session_id=?", first);
        jdbc.update("UPDATE ai.chat_session SET hidden_at=CURRENT_TIMESTAMP WHERE session_id=?", first);
        assertThat(jdbc.queryForMap("SELECT retention_until,expires_at FROM ai.chat_session WHERE session_id=?", first)).isEqualTo(before);
        rejects(() -> jdbc.update("UPDATE ai.chat_msg SET criteria='{\"virtualCostAssumption\":{}}'::jsonb WHERE msg_id=?", msg));
        rejects(() -> jdbc.update("UPDATE ai.chat_msg SET feedback_rating_cd='UNKNOWN' WHERE msg_id=?", msg));
        jdbc.update("UPDATE ai.chat_msg SET feedback_rating_cd='HELPFUL' WHERE msg_id=?", msg);
        jdbc.update("UPDATE ai.chat_msg SET feedback_rating_cd='INACCURATE' WHERE msg_id=?", msg);
        assertThat(jdbc.queryForObject("SELECT feedback_rating_cd FROM ai.chat_msg WHERE msg_id=?", String.class, msg)).isEqualTo("INACCURATE");
    }
}
