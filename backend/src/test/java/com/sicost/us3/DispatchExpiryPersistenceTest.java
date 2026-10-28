package com.sicost.us3;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.apiops.ProjectBoardMapper;
import com.sicost.assignments.*;
import com.sicost.changes.AssignmentChangeMapper;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DispatchExpiryPersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AssignmentTestSupport fixtures;
    AnnotationConfigApplicationContext context;
    AssignmentChangeMapper mapper;
    DispatchExpiryService dispatch;
    MockMvc assignmentApi;
    long project, developer;

    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(adminUrl, user, password);
                var statement = connection.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures = new AssignmentTestSupport(source);
        project = fixtures.createProject();
        developer = fixtures.createDeveloper();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/assignments/AssignmentMapper.xml"),
            new ClassPathResource("mappers/changes/AssignmentChangeMapper.xml"),
            new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"));
        factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(AssignmentChangeMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(AssignmentTestSupport.Transactions.class, AssignmentService.class, DispatchExpiryService.class,
            AssignmentConcurrencyService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(AssignmentMapper.class, () -> session.getMapper(AssignmentMapper.class));
        context.registerBean(AssignmentChangeMapper.class, () -> mapper);
        context.registerBean(ProjectBoardMapper.class, () -> session.getMapper(ProjectBoardMapper.class));
        context.registerBean(AssignmentConcurrencyMapper.class, () -> session.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
            () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> AssignmentTestSupport.CLOCK);
        context.refresh();
        dispatch = context.getBean(DispatchExpiryService.class);
        assignmentApi = MockMvcBuilders.standaloneSetup(new AssignmentController(
            context.getBean(AssignmentService.class), fixtures.json, AssignmentTestSupport.CLOCK))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }

    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close();
        if (fixtures != null) fixtures.close();
        if (source == null) return;
        try (var connection = DriverManager.getConnection(adminUrl, user, password);
                var statement = connection.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }

    JsonNode create(long projectId, long developerId, String start, String end) throws Exception {
        return fixtures.createAssignment(assignmentApi, Map.of("prj_id", projectId, "dev_id", developerId,
            "job_cd", "BE", "bgn_dt", start, "end_dt", end, "asgn_rt", 1,
            "asgn_se_cd", "NEW", "rsn_cd", "CUST"));
    }

    @Test void minStartSurvivesGapAndIgnoresOtherProjectAndOtherDeveloper() throws Exception {
        create(project, developer, "2024-11-20", "2025-06-30");
        var second = create(project, developer, "2025-09-01", "2026-12-31");
        create(fixtures.createProject(), developer, "2023-01-01", "2024-01-01");
        create(project, fixtures.createDeveloper(), "2023-01-01", "2024-01-01");
        var first = mapper.firstStart(second.path("asgn_id").asLong());
        assertThat(first).isEqualTo("2024-11-20");
        assertThat(com.sicost.common.validation.DispatchExpiryCalculator.expiry(first, 24)).isEqualTo("2026-11-19");
    }

    @Test void incomingDeveloperStartsOwnClock() throws Exception {
        create(project, developer, "2024-11-20", "2025-06-30");
        var incoming = create(project, fixtures.createDeveloper(), "2025-09-01", "2026-12-31");
        assertThat(mapper.firstStart(incoming.path("asgn_id").asLong())).isEqualTo("2025-09-01");
    }

    @Test void nonDispatchDatabaseSnapshotUsesCurrentProjectContractAndEffectiveParameters() throws Exception {
        var row = create(project, developer, "2026-10-01", "2026-11-19");
        var snapshot = dispatch.snapshot(List.of(row.path("asgn_id").asLong()));
        assertThat(snapshot.today()).isEqualTo("2026-10-14");
        assertThat(snapshot.assignments().values()).singleElement().satisfies(value -> {
            assertThat(value.dispatch_display()).isEqualTo("–");
            assertThat(value.dispatch_expiry_dt()).isNull();
        });
    }
}
