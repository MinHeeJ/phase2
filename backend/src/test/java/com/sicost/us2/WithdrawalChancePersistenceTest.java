package com.sicost.us2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sicost.changes.*;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import com.sicost.common.validation.AssignmentRules;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WithdrawalChancePersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;
    AnnotationConfigApplicationContext context;
    MockMvc mvc;
    long assignment;

    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures = new AssignmentTestSupport(source);
        long project = fixtures.createProject(), developer = fixtures.createDeveloper();
        assignment = jdbc.queryForObject("SELECT nextval('asgn.seq_asgn_id')", Long.class);
        jdbc.update("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,ext_psblty_cd,created_at,updated_at) VALUES(?,?,?,?,'BE','2026-09-01','2026-12-31',NULL,TIMESTAMP '2026-10-13 12:00:00.123456',TIMESTAMP '2026-10-13 12:00:00.123456')", assignment, AssignmentRules.allocated(assignment), project, developer);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/changes/AssignmentChangeMapper.xml"), new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"));
        factory.afterPropertiesSet(); var session = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext();
        context.register(AssignmentTestSupport.Transactions.class, AssignmentChangeService.class, AssignmentConcurrencyService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(AssignmentChangeMapper.class, () -> session.getMapper(AssignmentChangeMapper.class));
        context.registerBean(AssignmentConcurrencyMapper.class, () -> session.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> AssignmentTestSupport.CLOCK);
        context.refresh();
        mvc = MockMvcBuilders.standaloneSetup(new AssignmentChangeController(context.getBean(AssignmentChangeService.class), fixtures.json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close(); if (fixtures != null) fixtures.close(); if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    String token() {
        return jdbc.queryForObject("SELECT coalesce(updated_at,created_at) FROM asgn.asgn WHERE asgn_id=?", LocalDateTime.class, assignment).toString();
    }
    byte[] body(String version, String chance) throws Exception {
        var input = new LinkedHashMap<String,Object>();
        input.put("updated_at", version); input.put("asgn_chg_ty_cd", "EXT_CHANCE");
        input.put("aply_dt", "2026-10-14"); input.put("ext_psblty_cd", chance);
        return fixtures.json.writeValueAsBytes(input);
    }
    long count() {
        return jdbc.queryForObject("SELECT count(*) FROM asgn.asgn_chg_hist WHERE asgn_id=? AND asgn_chg_ty_cd='EXT_CHANCE'", Long.class, assignment);
    }
    @Test void anonymousChangePersistsOneHistoryAndStaleSecondWindowCannotOverwrite() throws Exception {
        String original = token();
        mvc.perform(post("/api/assignments/{id}/changes", assignment).contentType("application/json").content(body(original, "H")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.assignment.ext_psblty_cd").value("H"))
            .andExpect(jsonPath("$.data.change.asgn_chg_ty_cd").value("EXT_CHANCE"));
        mvc.perform(post("/api/assignments/{id}/changes", assignment).contentType("application/json").content(body(original, "L")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_ASSIGNMENT"));
        assertThat(jdbc.queryForObject("SELECT ext_psblty_cd FROM asgn.asgn WHERE asgn_id=?", String.class, assignment)).isEqualTo("H");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT aft_val FROM asgn.asgn_chg_hist WHERE asgn_id=? AND asgn_chg_ty_cd='EXT_CHANCE'", String.class, assignment)).isEqualTo("높음");
    }
    @Test void identicalValueDoesNotAddHistory() throws Exception {
        mvc.perform(post("/api/assignments/{id}/changes", assignment).contentType("application/json").content(body(token(), "H"))).andExpect(status().isOk());
        mvc.perform(post("/api/assignments/{id}/changes", assignment).contentType("application/json").content(body(token(), "H"))).andExpect(status().isBadRequest());
        assertThat(count()).isEqualTo(1);
    }
    @Test void historyFailureRollsBackChanceAndVersion() throws Exception {
        String original = token();
        jdbc.execute("ALTER TABLE asgn.asgn_chg_hist ADD CONSTRAINT reject_chance_history CHECK(false) NOT VALID");
        try {
            mvc.perform(post("/api/assignments/{id}/changes", assignment).contentType("application/json").content(body(original, "H")));
        } catch (Exception ignored) { /* MVC may propagate the database exception. */ }
        assertThat(jdbc.queryForObject("SELECT ext_psblty_cd FROM asgn.asgn WHERE asgn_id=?", String.class, assignment)).isNull();
        assertThat(token()).isEqualTo(original);
        assertThat(count()).isZero();
    }
}
