package com.si.cost.shared;

import com.si.cost.common.user.DataScope;
import java.math.BigDecimal;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SharedReadMapperIT {
    @Test void realPostgresQueriesPreservePhysicalColumnsActiveFilteringAndEffectiveDates() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        String user = System.getenv("TEST_DB_USERNAME");
        String password = System.getenv("TEST_DB_PASSWORD");
        Flyway.configure().dataSource(url, user, password).defaultSchema("public")
                .locations("classpath:db/migration").load().migrate();
        var source = new UnpooledDataSource("org.postgresql.Driver", url, user, password);
        var configuration = new Configuration(new Environment("contract", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        String resource = "mappers/shared/SharedReadMapper.xml";
        try (var xml = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(false)) {
            var mapper = session.getMapper(SharedReadMapper.class);
            assertThat(mapper.groupExists("JOB")).isTrue();
            var codes = mapper.codeOptions("JOB", DataScope.ALL);
            assertThat(codes).hasSize(12);
            assertThat(codes).extracting(SharedReadModels.CodeOption::code).contains("BE", "OPS");
            try (var statement = session.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE cmn.cd_dtl SET use_yn='N' WHERE cd_grp_id='JOB' AND dtl_cd='OPS'");
                statement.executeUpdate("UPDATE sys.menu SET use_yn='N' WHERE scrn_id='UI-27'");
            }
            session.clearCache();
            assertThat(mapper.codeOptions("JOB", DataScope.ALL)).extracting(SharedReadModels.CodeOption::code).doesNotContain("OPS");
            var menus = mapper.menus(DataScope.ALL);
            assertThat(menus).extracting(SharedReadModels.MenuRow::screenId).doesNotContain("UI-27");
            assertThat(menus.stream().filter(row -> row.parentId() == null).count()).isEqualTo(10);
            assertThat(mapper.monthClose("2026-09", "2026-10", DataScope.ALL))
                    .containsExactly(new SharedReadModels.MonthClose("2026-09", "CLOSED"), new SharedReadModels.MonthClose("2026-10", "OPEN"));
            assertThat(mapper.effectiveParam("VAT_RATE", "2026-10", DataScope.ALL).numberValue()).isEqualByComparingTo("10");
            try (var statement = session.getConnection().createStatement()) {
                statement.executeUpdate("INSERT INTO cmn.oprt_std (oprt_std_id,aply_bgn_ym,oprt_std_nm,num_val,unit_nm,use_plc_desc) "
                        + "SELECT oprt_std_id,'9999-12',oprt_std_nm,11,unit_nm,use_plc_desc FROM cmn.oprt_std "
                        + "WHERE oprt_std_id='VAT_RATE' AND aply_bgn_ym='2000-01'");
            }
            session.clearCache();
            assertThat(mapper.effectiveParam("VAT_RATE", "2026-10", DataScope.ALL).numberValue()).isEqualByComparingTo(BigDecimal.TEN);
            assertThat(mapper.effectiveParam("VAT_RATE", "9999-12", DataScope.ALL).numberValue()).isEqualByComparingTo("11");
            assertThat(mapper.effectiveParam("VAT_RATE", "0001-01", DataScope.ALL)).isNull();
            assertThat(mapper.effectiveParam("AR_AGING_DAYS", "2026-10", DataScope.ALL).textValue()).isEqualTo("30,60,90");
            // Direct JDBC writes do not mark the MyBatis session dirty.
            session.rollback(true);
        }
    }
}
