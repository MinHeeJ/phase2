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
        flyway = Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").target("2").load();
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
        Map<String, Integer> counts = Map.of("cmn.cd_grp", 36, "cmn.cd_dtl", 189, "sys.role", 5, "sys.menu", 41, "sys.role_menu_auth", 155, "fin.mth_cls", 10);
        for (var entry : counts.entrySet()) assertThat(scalar("SELECT count(*) FROM " + entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
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
    @Test void bankMigrationAddsOnlyApprovedCodesAndPreservesExistingData() throws Exception {
        String originalCodes = snapshot("SELECT d FROM cmn.cd_dtl d WHERE cd_grp_id <> 'BANK' ORDER BY cd_grp_id,dtl_cd");
        String originalPartners = snapshot("SELECT p FROM mst.ptnr p ORDER BY ptnr_id");
        String originalParts = snapshot("SELECT p FROM mst.bsn_part p ORDER BY bsn_part_id");
        String originalStandards = snapshot("SELECT h FROM mst.bsn_part_std_hist h ORDER BY part_std_hist_id");
        assertThat(scalar("SELECT count(*) FROM cmn.cd_dtl WHERE cd_grp_id='BANK'")).isZero();
        flyway = Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        new SchemaValidator(source, new ObjectMapper()).validate();
        assertThat(scalar("SELECT count(*) FROM cmn.cd_dtl")).isEqualTo(199);
        assertThat(scalar("SELECT count(*) FROM cmn.cd_dtl WHERE cd_grp_id='BANK' AND use_yn='Y' AND created_by IS NULL")).isEqualTo(10);
        String[] codes = {"KB", "SHINHAN", "WOORI", "HANA", "NH", "IBK", "SC", "KAKAO", "TOSS", "KBANK"};
        String[] names = {"국민은행", "신한은행", "우리은행", "하나은행", "NH농협은행", "IBK기업은행", "SC제일은행", "카카오뱅크", "토스뱅크", "케이뱅크"};
        try (var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT dtl_cd,cd_nm,sort_seq FROM cmn.cd_dtl WHERE cd_grp_id='BANK' ORDER BY sort_seq")) {
            for (int i=0;i<codes.length;i++) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(codes[i]);
                assertThat(rows.getString(2)).isEqualTo(names[i]);
                assertThat(rows.getInt(3)).isEqualTo(i+1);
            }
            assertThat(rows.next()).isFalse();
        }
        var factory = new org.mybatis.spring.SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mappers/shared/SharedReadMapper.xml"));
        factory.afterPropertiesSet();
        var mapper = new org.mybatis.spring.SqlSessionTemplate(factory.getObject()).getMapper(com.sicost.shared.SharedReadMapper.class);
        assertThat(mapper.codeOptions("BANK")).extracting(com.sicost.shared.SharedReadModels.CodeOption::code).containsExactly(codes);
        assertThat(mapper.codeOptions("BANK")).extracting(com.sicost.shared.SharedReadModels.CodeOption::name).containsExactly(names);
        assertThat(snapshot("SELECT d FROM cmn.cd_dtl d WHERE cd_grp_id <> 'BANK' ORDER BY cd_grp_id,dtl_cd")).isEqualTo(originalCodes);
        assertThat(snapshot("SELECT p FROM mst.ptnr p ORDER BY ptnr_id")).isEqualTo(originalPartners);
        assertThat(snapshot("SELECT p FROM mst.bsn_part p ORDER BY bsn_part_id")).isEqualTo(originalParts);
        assertThat(snapshot("SELECT h FROM mst.bsn_part_std_hist h ORDER BY part_std_hist_id")).isEqualTo(originalStandards);
        Map<String, Integer> checksums = new LinkedHashMap<>();
        for (var migration : flyway.info().applied()) checksums.put(migration.getVersion().toString(), migration.getChecksum());
        String bankRows = snapshot("SELECT d FROM cmn.cd_dtl d WHERE cd_grp_id='BANK' ORDER BY sort_seq");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        for (var migration : flyway.info().applied()) assertThat(migration.getChecksum()).isEqualTo(checksums.get(migration.getVersion().toString()));
        assertThat(snapshot("SELECT d FROM cmn.cd_dtl d WHERE cd_grp_id='BANK' ORDER BY sort_seq")).isEqualTo(bankRows);
    }
    String snapshot(String sql) throws Exception {
        var values = new java.util.ArrayList<String>();
        try (var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return String.join("\n", values);
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
