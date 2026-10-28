package com.sicost.us5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.sicost.common.AssignmentTestSupport;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static com.sicost.us5.ShortageModels.*;

class ShortagePersistenceTest {
    String url, user, password, database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;
    long project, developer;
    @BeforeEach void setup() throws Exception {
        url = System.getenv("TEST_DATABASE_URL"); user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(url != null && !url.isBlank() && user != null && password != null, "PostgreSQL test connection not configured");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) { com.sicost.support.TestDatabaseDdl.create(s, database); }
        source = new DriverManagerDataSource(url.substring(0, url.lastIndexOf('/') + 1) + database, user, password);
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures = new AssignmentTestSupport(source); project = fixtures.createProject(); developer = fixtures.createDeveloper();
        jdbc.update("UPDATE prj.prj SET prfm_bgn_dt='2025-01-01',cur_end_dt='2027-12-31' WHERE prj_id=?", project);
    }
    @AfterEach void cleanup() throws Exception {
        if (fixtures != null) fixtures.close();
        if (source != null) try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) { com.sicost.support.TestDatabaseDdl.drop(s, database); }
    }
    @Test void monthlyAggregationExcludesCancelledAndDoesNotOffsetOtherMonths() {
        jdbc.update("INSERT INTO prj.psn_plan(prj_id,plan_ym,job_cd,grd_cd,need_mm_qty) VALUES(?,'2026-03','BE','',3),(?,'2026-04','BE','',3),(?,'2026-12','BE','',1),(?,'2027-01','BE','',5)", project, project, project, project);
        Long id = jdbc.queryForObject("SELECT nextval('asgn.seq_asgn_id')", Long.class);
        Long cancelled = jdbc.queryForObject("SELECT nextval('asgn.seq_asgn_id')", Long.class);
        jdbc.update("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES(?,?,?,?, 'BE','2026-01-01','2026-12-31','ACTIVE')", id, "A-" + id, project, developer);
        jdbc.update("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES(?,?,?,?, 'BE','2026-01-01','2026-12-31','CANCELLED')", cancelled, "A-" + cancelled, project, developer);
        jdbc.update("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(?,'2026-03',0.50),(?,'2026-04',3.50),(?,'2026-12',0.90),(?,'2026-03',1.00)", id, id, id, cancelled);
        var rows = new ShortageRepository(jdbc).find(new Filter(2026, project, "BE", 1, 50), "2026-10");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).month()).isEqualTo("2026-03");
        assertThat(rows.get(0).shortageMm()).isEqualByComparingTo("2.50");
        assertThat(new ShortageRepository(jdbc).find(new Filter(2026, project, "QA", 1, 50), "2026-10")).isEmpty();
    }
}
