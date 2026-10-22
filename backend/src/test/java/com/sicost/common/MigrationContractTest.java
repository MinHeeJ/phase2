package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.persistence.SchemaContractVerifier;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class MigrationContractTest {
    private static String resource(String name) throws Exception {
        return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void originalSnapshotHasOnlyPermittedMechanicalDifferences() throws Exception {
        String original = resource("db/reference/si_cost_schema_pg16_std.sql");
        String expected = original.replaceAll("(?m)^DROP SCHEMA[^;]*;\\r?\\n", "")
                .replaceAll("\\bCREATE (TABLE|INDEX|SEQUENCE) (?!IF NOT EXISTS)", "CREATE $1 IF NOT EXISTS ")
                .replaceAll("(?m)[ \\t]+$", "");
        assertThat(resource("db/migration/V1__schema.sql")).isEqualTo(expected);
        assertThat(resource("schema.sql")).isEqualTo(expected);
        assertThat(expected.lines().filter(line -> line.startsWith("CREATE SCHEMA ")).count()).isEqualTo(11);
        assertThat(expected.lines().filter(line -> line.startsWith("CREATE TABLE IF NOT EXISTS ")).count()).isEqualTo(80);
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
    class PostgreSql {
        private String database;
        private DriverManagerDataSource dataSource;
        private JdbcTemplate jdbc;
        private Flyway flyway;

        private Connection admin() throws Exception {
            return DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"), System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD"));
        }

        @BeforeEach
        void restoreIndependentDatabase() throws Exception {
            database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = admin()) {
                TestDatabaseIdentifier.create(connection, database);
            }
            String source = System.getenv("TEST_DATABASE_URL");
            int query = source.indexOf('?');
            String base = query < 0 ? source : source.substring(0, query);
            String url = base.substring(0, base.lastIndexOf('/') + 1) + database + (query < 0 ? "" : source.substring(query));
            dataSource = new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD"));
            jdbc = new JdbcTemplate(dataSource);
            flyway = Flyway.configure().dataSource(dataSource).defaultSchema("public").locations("classpath:db/migration").load();
            flyway.migrate();
        }

        @AfterEach
        void removeIndependentDatabase() throws Exception {
            if (database != null) try (var connection = admin()) {
                TestDatabaseIdentifier.drop(connection, database);
            }
        }

        @Test
        void fullInventoryAndSeedCountsMatchOriginal() {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('cmn','sys','mst','bsn','prj','asgn','hr','cst','bill','fin','ai')", Integer.class)).isEqualTo(11);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema IN ('cmn','sys','mst','bsn','prj','asgn','hr','cst','bill','fin','ai') AND table_type='BASE TABLE'", Integer.class)).isEqualTo(80);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cmn.cd_grp", Integer.class)).isEqualTo(36);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cmn.cd_dtl", Integer.class)).isEqualTo(189);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys.role", Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys.menu", Integer.class)).isEqualTo(41);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys.role_menu_auth", Integer.class)).isEqualTo(155);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fin.mth_cls", Integer.class)).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys.menu WHERE scrn_id IS NOT NULL", Integer.class)).isEqualTo(31);
            assertThat(jdbc.queryForList("SELECT scrn_id FROM sys.menu WHERE mbl_expsr_yn='N' ORDER BY scrn_id", String.class))
                    .containsExactly("UI-10","UI-14","UI-20","UI-23","UI-24","UI-25","UI-27");
        }

        @Test
        void sourceActorsAndBusinessSeedsAreUsable() {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cmn.cd_dtl WHERE cd_grp_id='JOB'", Integer.class)).isEqualTo(12);
            assertThat(jdbc.queryForList("SELECT dtl_cd FROM cmn.cd_dtl WHERE cd_grp_id='JOB'", String.class)).contains("BE", "OPS");
            assertThat(jdbc.queryForObject("SELECT attr1_val FROM cmn.cd_dtl WHERE cd_grp_id='POSITION' AND dtl_cd='TL'", String.class)).isEqualTo("200000");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cmn.cd_dtl WHERE cd_grp_id='CONTRACT_TYPE' AND dtl_cd='TK'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT num_val FROM cmn.oprt_std WHERE oprt_std_id='VAT_RATE'", java.math.BigDecimal.class)).isEqualByComparingTo("10");
            assertThat(jdbc.queryForObject("SELECT use_yn FROM cmn.cd_dtl WHERE cd_grp_id='JOB' AND dtl_cd='BE'", String.class)).isEqualTo("Y");
        }

        @Test
        void allTenMonthStatesAndLocalClosingTimesMatch() {
            for (int month = 1; month <= 9; month++) {
                YearMonth ym = YearMonth.of(2026, month);
                assertThat(jdbc.queryForObject("SELECT cls_stts_cd FROM fin.mth_cls WHERE cls_ym=?", String.class, ym.toString())).isEqualTo("CLOSED");
                assertThat(jdbc.queryForObject("SELECT cls_at FROM fin.mth_cls WHERE cls_ym=?", LocalDateTime.class, ym.toString())).isEqualTo(ym.plusMonths(1).atDay(5).atStartOfDay());
            }
            assertThat(jdbc.queryForObject("SELECT cls_stts_cd FROM fin.mth_cls WHERE cls_ym='2026-10'", String.class)).isEqualTo("OPEN");
            assertThat(jdbc.queryForObject("SELECT cls_at FROM fin.mth_cls WHERE cls_ym='2026-10'", LocalDateTime.class)).isNull();
        }

        @Test
        void schemaMetadataIsValidatedSeparatelyFromFlywaySuccess() throws Exception {
            new SchemaContractVerifier(dataSource, new ObjectMapper()).verify();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='cst' AND column_name LIKE '%\\_amt' ESCAPE '\\' AND data_type<>'bigint'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='cmn' AND table_name IN ('oprt_std','excel_map') AND column_name='updated_at'", Integer.class)).isZero();
        }

        @Test
        void reinitializingDoesNotDuplicateRowsOrChangeMigrationChecksums() {
            var before = jdbc.queryForList("SELECT version, checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank");
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForList("SELECT version, checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cmn.cd_dtl", Integer.class)).isEqualTo(189);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fin.mth_cls", Integer.class)).isEqualTo(10);
        }

        @Test
        void applicationUpdatedAtIsPreservedAndFallbackStillWorks() {
            var supplied = LocalDateTime.of(2026,10,1,9,0);
            jdbc.update("UPDATE cmn.cd_dtl SET cd_nm=cd_nm, updated_at=? WHERE cd_grp_id='JOB' AND dtl_cd='BE'", supplied);
            assertThat(jdbc.queryForObject("SELECT updated_at FROM cmn.cd_dtl WHERE cd_grp_id='JOB' AND dtl_cd='BE'", LocalDateTime.class)).isEqualTo(supplied);
            jdbc.update("UPDATE cmn.cd_dtl SET cd_nm=cd_nm WHERE cd_grp_id='JOB' AND dtl_cd='BE'");
            assertThat(jdbc.queryForObject("SELECT updated_at FROM cmn.cd_dtl WHERE cd_grp_id='JOB' AND dtl_cd='BE'", LocalDateTime.class)).isNotEqualTo(supplied);
        }
    }
}
