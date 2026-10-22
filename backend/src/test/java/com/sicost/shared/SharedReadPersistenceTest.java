package com.sicost.shared;

import com.sicost.common.TestDatabaseIdentifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class SharedReadPersistenceTest {
    @Test
    void typedXmlParsesWithActualRecordConstructorsAndBoundParameters() throws Exception {
        var configuration = new Configuration();
        try (var input = new ClassPathResource("mappers/shared/SharedReadMapper.xml").getInputStream()) {
            new XMLMapperBuilder(input, configuration, "mappers/shared/SharedReadMapper.xml", configuration.getSqlFragments()).parse();
        }
        assertThat(configuration.hasMapper(SharedReadMapper.class)).isTrue();
        var statement = configuration.getMappedStatement("com.sicost.shared.SharedReadMapper.parameter");
        var bound = statement.getBoundSql(java.util.Map.of("key","VAT_RATE","month","2026-10"));
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("key","month");
        assertThat(statement.getResultMaps().get(0).getConstructorResultMappings()).hasSize(7);
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
    class PostgreSql {
        private String database;
        private JdbcTemplate jdbc;
        private SqlSessionFactory sessions;

        private Connection admin() throws Exception {
            return DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"), System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD"));
        }

        @BeforeEach
        void restoreIndependentDatabase() throws Exception {
            database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = admin()) { TestDatabaseIdentifier.create(connection, database); }
            String source = System.getenv("TEST_DATABASE_URL");
            int query = source.indexOf('?');
            String base = query < 0 ? source : source.substring(0, query);
            String url = base.substring(0, base.lastIndexOf('/') + 1) + database + (query < 0 ? "" : source.substring(query));
            var dataSource = new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD"));
            jdbc = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).defaultSchema("public").locations("classpath:db/migration").load().migrate();
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new ClassPathResource("mappers/shared/SharedReadMapper.xml"));
            sessions = factory.getObject();
        }

        @AfterEach
        void removeIndependentDatabase() throws Exception {
            if (database != null) try (var connection = admin()) { TestDatabaseIdentifier.drop(connection, database); }
        }

        @Test
        void activeCodesFilterAndOrderComeFromDatabaseWithoutChangingSavedNames() {
            jdbc.update("UPDATE cmn.cd_dtl SET use_yn='N' WHERE cd_grp_id='JOB' AND dtl_cd='OPS'");
            try (var session = sessions.openSession()) {
                var mapper = session.getMapper(SharedReadMapper.class);
                assertThat(mapper.groupExists("JOB")).isTrue();
                assertThat(mapper.groupExists("ABSENT")).isFalse();
                assertThat(mapper.codes("JOB")).extracting(CodeOption::code).doesNotContain("OPS")
                        .containsExactlyElementsOf(jdbc.queryForList("SELECT dtl_cd FROM cmn.cd_dtl WHERE cd_grp_id='JOB' AND use_yn='Y' ORDER BY sort_seq,dtl_cd", String.class));
                assertThat(jdbc.queryForObject("SELECT cd_nm FROM cmn.cd_dtl WHERE cd_grp_id='JOB' AND dtl_cd='OPS'", String.class)).isNotBlank();
                assertThat(mapper.codes("POSITION").stream().filter(row -> row.code().equals("TL")).findFirst().orElseThrow().attr1()).isEqualTo("200000");
            }
        }

        @Test
        void menusRespectActiveParentAndChildAndPreserveMobileFlag() {
            jdbc.update("UPDATE sys.menu SET use_yn='N' WHERE scrn_id='UI-03'");
            try (var session = sessions.openSession()) {
                var tree = new SharedReadService(session.getMapper(SharedReadMapper.class)).menus(com.sicost.common.security.DataScope.ALL);
                assertThat(tree).hasSize(10);
                assertThat(tree.stream().flatMap(node -> node.children().stream())).noneMatch(node -> "UI-03".equals(node.screenId()));
                assertThat(tree.stream().flatMap(node -> node.children().stream()).filter(node -> "UI-27".equals(node.screenId())).findFirst().orElseThrow().mobile()).isFalse();
            }
            jdbc.update("UPDATE sys.menu SET use_yn='N' WHERE menu_id=(SELECT upr_menu_id FROM sys.menu WHERE scrn_id='UI-27')");
            try (var session = sessions.openSession()) {
                assertThat(new SharedReadService(session.getMapper(SharedReadMapper.class)).menus(com.sicost.common.security.DataScope.ALL)
                        .stream().flatMap(node -> node.children().stream())).noneMatch(node -> "UI-27".equals(node.screenId()));
            }
        }

        @Test
        void monthRangeDoesNotSynthesizeMissingRowsEvenForFullCalendar() {
            try (var session = sessions.openSession()) {
                var mapper = session.getMapper(SharedReadMapper.class);
                assertThat(mapper.months("2026-09","2026-11")).containsExactly(new MonthClose("2026-09","CLOSED"),new MonthClose("2026-10","OPEN"));
                assertThat(mapper.months("0001-01","9999-12")).hasSize(10);
                assertThat(mapper.months("2027-01","2027-12")).isEmpty();
            }
        }

        @Test
        void futureVersionCannotChangePastMonthAndBeforeFirstVersionIsAbsent() {
            jdbc.update("INSERT INTO cmn.oprt_std(oprt_std_id,aply_bgn_ym,oprt_std_nm,num_val,unit_nm,use_plc_desc) SELECT oprt_std_id,'2026-11',oprt_std_nm,11,unit_nm,use_plc_desc FROM cmn.oprt_std WHERE oprt_std_id='VAT_RATE' ORDER BY aply_bgn_ym DESC LIMIT 1");
            try (var session = sessions.openSession()) {
                var mapper = session.getMapper(SharedReadMapper.class);
                assertThat(mapper.parameter("VAT_RATE","2026-10").num_val()).isEqualByComparingTo("10");
                assertThat(mapper.parameter("VAT_RATE","2026-11").num_val()).isEqualByComparingTo("11");
                assertThat(mapper.parameter("VAT_RATE","0001-01")).isNull();
                assertThat(mapper.parameter("ABSENT","2026-10")).isNull();
            }
        }
    }
}
