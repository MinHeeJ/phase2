package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sicost.apiops.ProjectBoardMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PlanGapMigrationTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    Flyway baseline;

    @Test void migrationContainsOnlyTheApprovedInsert() throws Exception {
        try (var input = new ClassPathResource("db/migration/V5__seed_plan_gap.sql").getInputStream()) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8).trim()).isEqualTo("""
                INSERT INTO cmn.oprt_std (oprt_std_id, aply_bgn_ym, oprt_std_nm, num_val, unit_nm, use_plc_desc)
                VALUES ('PLAN_GAP_TOLERANCE', '2000-01', '계획 대비 투입 맞음 범위(±)', 0.10, 'MM', 'UI-11, UI-01 인원 계획');
                """.trim());
        }
    }

    void isolatedDatabase() throws Exception {
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
            .locations("classpath:db/migration").target("4").load();
        baseline.migrate();
    }

    @AfterEach void removeDatabase() throws Exception {
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

    Map<String, Long> rowCounts() {
        var tables = jdbc.queryForList("""
            SELECT t.table_schema || '.' || t.table_name
            FROM information_schema.tables t
            WHERE t.table_type='BASE TABLE' AND t.table_schema NOT IN ('public','information_schema','pg_catalog')
            ORDER BY t.table_schema,t.table_name
            """, String.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : tables) {
            assertThat(table).matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*");
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        return counts;
    }

    @Test void upgradeAddsExactlyOneStandardAndPreservesPriorChecksumsAndSeeds() throws Exception {
        isolatedDatabase();
        Map<String, Integer> checksums = new LinkedHashMap<>();
        for (var migration : baseline.info().applied()) {
            checksums.put(migration.getVersion().toString(), migration.getChecksum());
        }
        var counts = rowCounts();
        var oldStandards = jdbc.queryForList("SELECT * FROM cmn.oprt_std ORDER BY oprt_std_id,aply_bgn_ym");
        var flyway = upgrade();
        assertThat(flyway.info().applied()).hasSize(5);
        for (var migration : flyway.info().applied()) {
            String version = migration.getVersion().toString();
            if (checksums.containsKey(version)) {
                assertThat(migration.getChecksum()).isEqualTo(checksums.get(version));
            }
        }
        counts.compute("cmn.oprt_std", (key, count) -> count + 1);
        assertThat(rowCounts()).isEqualTo(counts);
        assertThat(jdbc.queryForList("""
            SELECT * FROM cmn.oprt_std WHERE oprt_std_id!='PLAN_GAP_TOLERANCE'
            ORDER BY oprt_std_id,aply_bgn_ym
            """)).isEqualTo(oldStandards);
        var row = jdbc.queryForMap("SELECT * FROM cmn.oprt_std WHERE oprt_std_id='PLAN_GAP_TOLERANCE'");
        assertThat(row).containsEntry("aply_bgn_ym", "2000-01")
            .containsEntry("oprt_std_nm", "계획 대비 투입 맞음 범위(±)")
            .containsEntry("unit_nm", "MM")
            .containsEntry("use_plc_desc", "UI-11, UI-01 인원 계획")
            .containsEntry("txt_val", null);
        assertThat((BigDecimal) row.get("num_val")).isEqualByComparingTo("0.10");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test void realMapperReturnsAllNineLatestEffectiveRowsWithoutFutureLeakage() throws Exception {
        isolatedDatabase();
        upgrade();
        jdbc.update("""
            INSERT INTO cmn.oprt_std(oprt_std_id,aply_bgn_ym,oprt_std_nm,num_val,txt_val)
            VALUES ('PLAN_GAP_TOLERANCE','2026-11','시험 허용 범위',0.25,NULL),
                ('PLAN_GAP_TOLERANCE','2027-01','시험 미래 범위',0.50,NULL),
                ('DISPATCH_MAX_MONTHS','2026-11','시험 최대 개월',18,NULL),
                ('DISPATCH_ALERT_DAYS','2026-11','시험 알림',NULL,'45,15')
            """);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"));
        factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        var mapper = session.getMapper(ProjectBoardMapper.class);
        var historical = mapper.params("2026-10");
        var current = mapper.params("2026-11");
        assertThat(current).hasSize(9);
        assertThat(current).extracting(p -> p.oprt_std_id()).doesNotHaveDuplicates();
        assertThat(current.stream().filter(p -> "PLAN_GAP_TOLERANCE".equals(p.oprt_std_id())).findFirst().orElseThrow().num_val())
            .isEqualByComparingTo("0.25");
        assertThat(historical.stream().filter(p -> "PLAN_GAP_TOLERANCE".equals(p.oprt_std_id())).findFirst().orElseThrow().num_val())
            .isEqualByComparingTo("0.10");
        assertThat(current.stream().filter(p -> "DISPATCH_MAX_MONTHS".equals(p.oprt_std_id())).findFirst().orElseThrow().num_val())
            .isEqualByComparingTo("18");
        assertThat(current.stream().filter(p -> "DISPATCH_ALERT_DAYS".equals(p.oprt_std_id())).findFirst().orElseThrow().txt_val())
            .isEqualTo("45,15");
        assertThat(current).filteredOn(p -> !p.oprt_std_id().startsWith("DISPATCH_")
            && !"PLAN_GAP_TOLERANCE".equals(p.oprt_std_id())).hasSize(6);
    }
}
