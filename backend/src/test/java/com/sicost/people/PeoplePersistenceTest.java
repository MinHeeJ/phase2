package com.sicost.people;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.people.PeopleModels.*;

class PeoplePersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    PeopleMapper mapper;
    @BeforeEach void setup() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/people/PeopleMapper.xml"));
        factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PeopleMapper.class);
    }
    @AfterEach void cleanup() throws Exception {
        if (source == null) return;
        try (var admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }
    @Test void realProjectionIncludesMissingMonthsGeneratedPayrollAndDeduplicatedExpenses() throws Exception {
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,main_job_cd,hire_dt) VALUES(901,'K-TEST-901','검증인력','FT','DEV','2026-01-01')");
            sql.execute("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt,alwnc_amt,sins_cmpny_amt,sins_emp_amt) VALUES(901,'2026-01','FT',100,20,10,5)");
            sql.execute("INSERT INTO cst.drct_expns(dev_id,expns_ym,expns_item_cd,expns_amt) VALUES(901,'2026-01','TEAM',7),(901,'2026-01','TEAM',3),(901,'2026-01','PEXP',4)");
            sql.execute("INSERT INTO mst.dev_work_stts_hist(dev_id,work_stts_cd,bgn_dt,end_dt) VALUES(901,'LEAVE','2026-02-01','2026-02-28')");
        }
        var people = mapper.people(new Period("2026-01", "2026-02")).stream().filter(p -> p.dev_id() == 901).toList();
        assertThat(people).hasSize(2);
        assertThat(people.get(0).indv_lbr_cst_amt()).isEqualTo(130);
        assertThat(people.get(0).real_pay_amt()).isEqualTo(115);
        assertThat(people.get(0).team_expns_amt()).isEqualTo(10);
        assertThat(people.get(0).project_expns_amt()).isEqualTo(4);
        assertThat(people.get(1).payroll_present()).isFalse();
        assertThat(people.get(1).on_leave()).isTrue();
        assertThat(mapper.personExists(901)).isTrue();
        mapper.download(new Download("개인원가수익.xlsx", "{\"rows\":1}", null));
        try (var connection = source.getConnection(); var sql = connection.createStatement();
             var result = sql.executeQuery("SELECT count(*) FROM sys.acs_hist WHERE scrn_id='UI-03' AND user_id IS NULL AND acs_ty_cd='DOWNLOAD'")) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }
}
