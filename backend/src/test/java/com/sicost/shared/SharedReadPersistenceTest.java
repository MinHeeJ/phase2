package com.sicost.shared;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SharedReadPersistenceTest {
    String adminUrl;
    String user;
    String password;
    String database;
    DriverManagerDataSource source;
    JdbcTemplate sql;
    org.apache.ibatis.session.SqlSession session;
    SharedReadMapper mapper;

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
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        sql = new JdbcTemplate(source);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/shared/SharedReadMapper.xml"));
        session = factory.getObject().openSession(true);
        mapper = session.getMapper(SharedReadMapper.class);
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (session != null) session.close();
        if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }
    @Test void optionsFilterInactiveRowsAndRespectSortWithoutChangingStoredNames() {
        assertThat(mapper.groupExists("JOB")).isTrue();
        assertThat(mapper.groupExists("UNKNOWN")).isFalse();
        assertThat(mapper.codeOptions("JOB")).hasSize(12);
        sql.update("UPDATE cmn.cd_dtl SET use_yn='N' WHERE cd_grp_id='JOB' AND dtl_cd='OPS'");
        sql.update("UPDATE cmn.cd_dtl SET sort_seq=-1 WHERE cd_grp_id='JOB' AND dtl_cd='BE'");
        session.clearCache();
        var rows = mapper.codeOptions("JOB");
        assertThat(rows).hasSize(11);
        assertThat(rows.get(0).code()).isEqualTo("BE");
        assertThat(rows).extracting(row -> row.code()).doesNotContain("OPS");
        assertThat(sql.queryForObject("SELECT cd_nm FROM cmn.cd_dtl WHERE cd_grp_id='JOB' AND dtl_cd='OPS'", String.class)).isNotBlank();
        assertThat(mapper.codeOptions("POSITION")).filteredOn(row -> row.code().equals("TL")).singleElement()
            .satisfies(row -> assertThat(row.attr1()).isEqualTo("200000"));
    }
    @Test void menuSqlFiltersUseFlagAndKeepsSnapshotInventoryAndMobileFlags() {
        var rows = mapper.menus();
        assertThat(rows).hasSize(41);
        assertThat(rows).filteredOn(row -> row.parentId() == null).hasSize(10);
        assertThat(rows).filteredOn(row -> row.screenId() != null).hasSize(31);
        assertThat(rows).filteredOn(row -> !row.mobile()).extracting(row -> row.screenId())
            .containsExactlyInAnyOrder("UI-10", "UI-20", "UI-14", "UI-23", "UI-24", "UI-25", "UI-27");
        Long id = rows.stream().filter(row -> "UI-27".equals(row.screenId())).findFirst().orElseThrow().menuId();
        sql.update("UPDATE sys.menu SET use_yn='N' WHERE menu_id=?", id);
        session.clearCache();
        assertThat(mapper.menus()).noneMatch(row -> row.menuId() == id);
        var tree = new SharedReadService(mapper).menus(com.sicost.common.security.DataScope.ALL);
        assertThat(tree).hasSize(10);
        assertThat(tree.stream().flatMap(row -> row.children().stream()).toList()).hasSize(30);
    }
    @Test void monthsAreInclusiveOrderedAndSparseAtCompleteBoundary() {
        assertThat(mapper.monthClose("2026-09", "2026-10")).extracting(row -> row.month()).containsExactly("2026-09", "2026-10");
        assertThat(mapper.monthClose("2026-09", "2026-10")).extracting(row -> row.status()).containsExactly("CLOSED", "OPEN");
        assertThat(mapper.monthClose("0001-01", "9999-12")).hasSize(10);
        assertThat(mapper.monthClose("2026-11", "2026-12")).isEmpty();
    }
    @Test void latestEffectiveVersionPreservesPastDecimalZeroAndUnknownAbsence() {
        assertThat(mapper.effectiveParam("VAT_RATE", "2026-10").numericValue()).isEqualByComparingTo("10");
        sql.update("INSERT INTO cmn.oprt_std(oprt_std_id,aply_bgn_ym,oprt_std_nm,num_val,unit_nm,use_plc_desc) SELECT oprt_std_id,'2026-11',oprt_std_nm,11.1250,unit_nm,use_plc_desc FROM cmn.oprt_std WHERE oprt_std_id='VAT_RATE' AND aply_bgn_ym='2000-01'");
        session.clearCache();
        assertThat(mapper.effectiveParam("VAT_RATE", "2026-10").numericValue()).isEqualByComparingTo("10");
        assertThat(mapper.effectiveParam("VAT_RATE", "2026-11").numericValue()).isEqualByComparingTo("11.1250");
        assertThat(mapper.effectiveParam("VAT_RATE", "9999-12").effectiveMonth()).isEqualTo("2026-11");
        assertThat(mapper.effectiveParam("VAT_RATE", "0001-01")).isNull();
        assertThat(mapper.effectiveParam("UNKNOWN", "2026-10")).isNull();
        sql.update("UPDATE cmn.oprt_std SET num_val=0 WHERE oprt_std_id='VAT_RATE' AND aply_bgn_ym='2026-11'");
        session.clearCache();
        assertThat(mapper.effectiveParam("VAT_RATE", "2026-11").numericValue()).isZero();
    }
}
