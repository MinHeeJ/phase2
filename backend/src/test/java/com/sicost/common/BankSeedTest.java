package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sicost.common.security.DataScope;
import com.sicost.shared.SharedReadMapper;
import com.sicost.shared.SharedReadService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class BankSeedTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    JdbcTemplate sql;
    Flyway flyway;

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "PostgreSQL 시험 연결이 없으면 생략합니다.");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "시험 DB 연결 설정이 없습니다.");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " TEMPLATE template0");
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").target("2").load().migrate();
        flyway = Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load();
        sql = new JdbcTemplate(source);
    }

    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }

    @Test void migrationAddsExactlyTheDeclaredActiveBankOptions() throws Exception {
        flyway.migrate();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/shared/SharedReadMapper.xml"));
        try (var session = factory.getObject().openSession(true)) {
            var service = new SharedReadService(session.getMapper(SharedReadMapper.class));
            var options = service.codeOptions("BANK", DataScope.ALL);
            assertThat(options).extracting(row -> row.code()).containsExactly("KB", "SHINHAN", "WOORI", "HANA", "NH", "IBK", "SC", "KAKAO", "TOSS", "KBANK");
            assertThat(options).extracting(row -> row.name()).containsExactly("국민은행", "신한은행", "우리은행", "하나은행", "NH농협은행", "IBK기업은행", "SC제일은행", "카카오뱅크", "토스뱅크", "케이뱅크");
            assertThat(options).allSatisfy(row -> {
                assertThat(row.attr1()).isNull();
                assertThat(row.attr2()).isNull();
                assertThat(row.attr3()).isNull();
            });
        }
        assertThat(sql.queryForList("SELECT sort_seq FROM cmn.cd_dtl WHERE cd_grp_id='BANK' ORDER BY sort_seq", Integer.class))
            .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test void migrationPreservesEveryExistingBusinessAndReferenceRow() {
        var codes = sql.queryForList("SELECT * FROM cmn.cd_dtl ORDER BY cd_grp_id,dtl_cd");
        var groups = sql.queryForList("SELECT * FROM cmn.cd_grp ORDER BY cd_grp_id");
        var partners = sql.queryForList("SELECT * FROM mst.ptnr ORDER BY ptnr_id");
        var parts = sql.queryForList("SELECT * FROM mst.bsn_part ORDER BY bsn_part_id");
        var standards = sql.queryForList("SELECT * FROM mst.bsn_part_std_hist ORDER BY part_std_hist_id");
        var months = sql.queryForList("SELECT * FROM fin.mth_cls ORDER BY cls_ym");
        var checksums = sql.queryForList("SELECT version,checksum FROM public.flyway_schema_history WHERE version IN ('1','2') ORDER BY installed_rank");
        flyway.migrate();
        assertThat(sql.queryForList("SELECT * FROM cmn.cd_dtl WHERE cd_grp_id <> 'BANK' ORDER BY cd_grp_id,dtl_cd")).isEqualTo(codes);
        assertThat(sql.queryForList("SELECT * FROM cmn.cd_grp ORDER BY cd_grp_id")).isEqualTo(groups);
        assertThat(sql.queryForList("SELECT * FROM mst.ptnr ORDER BY ptnr_id")).isEqualTo(partners).isEmpty();
        assertThat(sql.queryForList("SELECT * FROM mst.bsn_part ORDER BY bsn_part_id")).isEqualTo(parts).hasSize(1);
        assertThat(sql.queryForList("SELECT * FROM mst.bsn_part_std_hist ORDER BY part_std_hist_id")).isEqualTo(standards);
        assertThat(sql.queryForList("SELECT * FROM fin.mth_cls ORDER BY cls_ym")).isEqualTo(months);
        assertThat(sql.queryForList("SELECT version,checksum FROM public.flyway_schema_history WHERE version IN ('1','2') ORDER BY installed_rank")).isEqualTo(checksums);
    }

    @Test void restartAndSeedReplayDoNotDuplicateOrOverwriteCodes() throws Exception {
        flyway.migrate();
        sql.update("UPDATE cmn.cd_dtl SET cd_nm=?,use_yn='N' WHERE cd_grp_id='BANK' AND dtl_cd='KB'", "사용자 변경 은행명");
        var before = sql.queryForList("SELECT * FROM cmn.cd_dtl WHERE cd_grp_id='BANK' ORDER BY sort_seq");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V3__seed_bank.sql"));
        }
        flyway.validate();
        assertThat(sql.queryForList("SELECT * FROM cmn.cd_dtl WHERE cd_grp_id='BANK' ORDER BY sort_seq")).isEqualTo(before).hasSize(10);
    }
}
