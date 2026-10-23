package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.persistence.SchemaValidator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SchemaMigrationTest {
    String adminUrl;
    String user;
    String password;
    String database;
    DriverManagerDataSource source;
    Flyway flyway;

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " TEMPLATE template0");
        }
        String url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database;
        source = new DriverManagerDataSource(url, user, password);
        flyway = Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load();
        flyway.migrate();
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }
    long scalar(String sql) throws Exception {
        try (Connection connection = source.getConnection(); var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }
    @Test void snapshotMetadataAndSeedCountsMatchSource() throws Exception {
        new SchemaValidator(source, new ObjectMapper()).validate();
        assertThat(scalar("SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('cmn','sys','mst','bsn','prj','asgn','hr','cst','bill','fin','ai')")).isEqualTo(11);
        assertThat(scalar("SELECT count(*) FROM information_schema.tables WHERE table_type='BASE TABLE' AND table_schema IN ('cmn','sys','mst','bsn','prj','asgn','hr','cst','bill','fin','ai')")).isEqualTo(80);
        Map<String, Integer> counts = Map.of("cmn.cd_grp", 36, "cmn.cd_dtl", 199, "sys.role", 5, "sys.menu", 41, "sys.role_menu_auth", 155, "fin.mth_cls", 10);
        for (var entry : counts.entrySet()) assertThat(scalar("SELECT count(*) FROM " + entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue().longValue());
        assertThat(scalar("SELECT count(*) FROM cmn.cd_dtl WHERE cd_grp_id='JOB'")).isEqualTo(12);
        assertThat(scalar("SELECT count(*) FROM cmn.cd_dtl WHERE cd_grp_id='JOB' AND dtl_cd IN ('BE','OPS')")).isEqualTo(2);
        assertThat(scalar("SELECT attr1_val::bigint FROM cmn.cd_dtl WHERE cd_grp_id='POSITION' AND dtl_cd='TL'")).isEqualTo(200000);
        assertThat(scalar("SELECT count(*) FROM cmn.cd_dtl WHERE cd_grp_id='CONTRACT_TYPE' AND dtl_cd='TK'")).isEqualTo(1);
        assertThat(scalar("SELECT num_val FROM cmn.oprt_std WHERE oprt_std_id='VAT_RATE' AND aply_bgn_ym='2000-01'")).isEqualTo(10);
        assertThat(scalar("SELECT count(*) FROM sys.menu WHERE scrn_id IS NOT NULL")).isEqualTo(31);
        assertThat(scalar("SELECT count(*) FROM sys.menu WHERE scrn_id='UI-22'")).isZero();
        assertThat(scalar("SELECT count(*) FROM sys.menu WHERE mbl_expsr_yn='N' AND scrn_id IN ('UI-10','UI-20','UI-14','UI-23','UI-24','UI-25','UI-27')")).isEqualTo(7);
    }
    @Test void closeTimestampsAndOpenDefaultsArePreserved() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT cls_ym,cls_stts_cd,cls_at,cls_user_id,rls_at FROM fin.mth_cls ORDER BY cls_ym")) {
            int month = 1;
            while (rows.next()) {
                assertThat(rows.getString(1)).isEqualTo(YearMonth.of(2026, month).toString());
                assertThat(rows.getString(2)).isEqualTo(month < 10 ? "CLOSED" : "OPEN");
                assertThat(rows.getObject(3, LocalDateTime.class)).isEqualTo(month < 10 ? YearMonth.of(2026, month).plusMonths(1).atDay(5).atStartOfDay() : null);
                assertThat(rows.getObject(4)).isNull();
                assertThat(rows.getObject(5)).isNull();
                month++;
            }
            assertThat(month).isEqualTo(11);
        }
    }
    @Test void restartDoesNotDuplicateSeedsOrChangeChecksums() throws Exception {
        Map<String, Integer> checksums = new LinkedHashMap<>();
        for (var migration : flyway.info().applied()) checksums.put(migration.getVersion().toString(), migration.getChecksum());
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        for (var migration : flyway.info().applied()) assertThat(migration.getChecksum()).isEqualTo(checksums.get(migration.getVersion().toString()));
        assertThat(scalar("SELECT count(*) FROM sys.menu")).isEqualTo(41);
    }
    @Test void wrongTypeIsRejectedBeyondSuccessfulFlyway() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE cmn.oprt_std ALTER COLUMN num_val TYPE decimal(17,3)");
        }
        assertThatThrownBy(() -> new SchemaValidator(source, new ObjectMapper()).validate()).hasMessageContaining("cmn.oprt_std.num_val");
    }
    @Test void lostCompositePrimaryKeyIsRejected() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE cmn.oprt_std DROP CONSTRAINT pk_oprt_std");
        }
        assertThatThrownBy(() -> new SchemaValidator(source, new ObjectMapper()).validate()).hasMessageContaining("primary key");
    }
}
