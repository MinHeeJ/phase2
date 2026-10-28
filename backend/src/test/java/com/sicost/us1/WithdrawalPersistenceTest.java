package com.sicost.us1;

import com.sicost.apiops.ProjectBoardMapper;
import com.sicost.assignments.*;
import com.sicost.changes.*;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WithdrawalPersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;
    AnnotationConfigApplicationContext context;
    MockMvc mvc;
    long project, developer;
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
        project = fixtures.createProject(); developer = fixtures.createDeveloper();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/us1/WithdrawalMapper.xml"),
            new ClassPathResource("mappers/assignments/AssignmentMapper.xml"),
            new ClassPathResource("mappers/changes/AssignmentChangeMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"),
            new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"));
        factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext();
        context.register(AssignmentTestSupport.Transactions.class, WithdrawalService.class, WithdrawalExportService.class,
            ExcelExportService.class, AssignmentService.class, AssignmentChangeService.class,
            AssignmentConcurrencyService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(WithdrawalMapper.class, () -> session.getMapper(WithdrawalMapper.class));
        context.registerBean(ProjectBoardMapper.class, () -> session.getMapper(ProjectBoardMapper.class));
        context.registerBean(AssignmentMapper.class, () -> session.getMapper(AssignmentMapper.class));
        context.registerBean(AssignmentChangeMapper.class, () -> session.getMapper(AssignmentChangeMapper.class));
        context.registerBean(AssignmentConcurrencyMapper.class, () -> session.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> AssignmentTestSupport.CLOCK);
        context.refresh();
        mvc = MockMvcBuilders.standaloneSetup(
            new WithdrawalController(context.getBean(WithdrawalService.class), context.getBean(WithdrawalExportService.class)),
            new AssignmentController(context.getBean(AssignmentService.class), fixtures.json, AssignmentTestSupport.CLOCK),
            new AssignmentChangeController(context.getBean(AssignmentChangeService.class), fixtures.json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close();
        if (fixtures != null) fixtures.close();
        if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    long assignment(long projectId, String start, String end) throws Exception {
        var payload = Map.<String, Object>of("prj_id", projectId, "dev_id", developer, "job_cd", "BE",
            "bgn_dt", start, "end_dt", end, "asgn_rt", 1, "asgn_se_cd", "NEW");
        return fixtures.createAssignment(mvc, payload).path("asgn_id").asLong();
    }
    Map<String, Object> change(long id, String type, String applied) {
        var body = new LinkedHashMap<String, Object>();
        body.put("updated_at", jdbc.queryForObject("SELECT coalesce(a.updated_at,a.created_at)::text FROM asgn.asgn a WHERE a.asgn_id=?", String.class, id).replace(' ', 'T'));
        body.put("asgn_chg_ty_cd", type); body.put("aply_dt", applied);
        return body;
    }
    @Test void realMapperReadsApiCreatedRowsAndNextAssignmentAcrossProjects() throws Exception {
        long first = assignment(project, "2026-10-14", "2026-11-20");
        long nextProject = fixtures.createProject();
        long next = assignment(nextProject, "2026-12-01", "2027-03-31");
        assignment(fixtures.createProject(), "2026-12-01", "2027-04-30");
        mvc.perform(get("/api/withdrawals").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.rows[0].asgn_id").value(first))
            .andExpect(jsonPath("$.data.rows[0].next_asgn_id").value(next))
            .andExpect(jsonPath("$.data.rows[0].remaining_days").value(37))
            .andExpect(jsonPath("$.data.summary.soonPeople").value(1))
            .andExpect(jsonPath("$.data.summary.noNextPeople").value(0));
    }
    @Test void correctedExtensionAndEarlyEndAreCountedFromEffectiveHistory() throws Exception {
        long id = assignment(project, "2026-10-14", "2026-11-20");
        var first = change(id, "EXTEND", "2026-11-21"); first.put("end_dt", "2026-12-20");
        mvc.perform(post("/api/assignments/{id}/changes", id).contentType("application/json").content(fixtures.json.writeValueAsBytes(first)))
            .andExpect(status().isOk());
        long history = jdbc.queryForObject("SELECT max(h.asgn_chg_id) FROM asgn.asgn_chg_hist h WHERE h.asgn_id=? AND h.asgn_chg_ty_cd='EXTEND'", Long.class, id);
        var second = change(id, "EXTEND", "2026-12-21"); second.put("end_dt", "2026-12-31");
        mvc.perform(post("/api/assignments/{id}/changes", id).contentType("application/json").content(fixtures.json.writeValueAsBytes(second)))
            .andExpect(status().isOk());
        var fix = new HashMap<String, Object>();
        fix.put("updated_at", change(id, "EXTEND", "2026-10-14").get("updated_at"));
        fix.put("aft_val", "정정된 기록"); fix.put("rsn_cd", "FIXERR");
        mvc.perform(post("/api/assignments/{id}/changes/{change}/fix", id, history).contentType("application/json").content(fixtures.json.writeValueAsBytes(fix)))
            .andExpect(status().isOk());
        var early = change(id, "EARLY_END", "2026-12-15"); early.put("rsn_cd", "PERSON");
        mvc.perform(post("/api/assignments/{id}/changes", id).contentType("application/json").content(fixtures.json.writeValueAsBytes(early)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/withdrawals").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].extend_count").value(1))
            .andExpect(jsonPath("$.data.rows[0].early_end").value(true));
    }
}
