package com.sicost.us4;

import static com.sicost.us4.PlanComparisonModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.assignments.*;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.staffplans.*;
import java.math.BigDecimal;
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

class PlanComparisonPersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AssignmentTestSupport fixtures;
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    PlanComparisonService service;
    MockMvc staffApi, assignmentApi;
    long project, developer;
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl,user,password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures = new AssignmentTestSupport(source);
        project = fixtures.createProject();
        developer = fixtures.createDeveloper();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/us4/PlanComparisonMapper.xml"),
            new ClassPathResource("mappers/staffplans/ProjectStaffPlanMapper.xml"),
            new ClassPathResource("mappers/assignments/AssignmentMapper.xml"));
        factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext();
        context.register(AssignmentTestSupport.Transactions.class, PlanComparisonService.class,
            ProjectStaffPlanService.class, AssignmentService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(PlanComparisonMapper.class, () -> session.getMapper(PlanComparisonMapper.class));
        context.registerBean(ProjectStaffPlanMapper.class, () -> session.getMapper(ProjectStaffPlanMapper.class));
        context.registerBean(AssignmentMapper.class, () -> session.getMapper(AssignmentMapper.class));
        context.registerBean(com.sicost.common.persistence.AssignmentConcurrencyService.class, () -> fixtures.concurrency);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> AssignmentTestSupport.CLOCK);
        context.refresh();
        service = context.getBean(PlanComparisonService.class);
        staffApi = MockMvcBuilders.standaloneSetup(new ProjectStaffPlanController(context.getBean(ProjectStaffPlanService.class),fixtures.json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        assignmentApi = MockMvcBuilders.standaloneSetup(new AssignmentController(context.getBean(AssignmentService.class),fixtures.json,AssignmentTestSupport.CLOCK))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close();
        if (fixtures != null) fixtures.close();
        if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl,user,password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    Map<String,Object> cell(String month, String job, String grade, String mm) {
        return Map.of("plan_ym",month,"job_cd",job,"grd_cd",grade,"need_mm_qty",new BigDecimal(mm));
    }
    void plans(List<Map<String,Object>> cells) throws Exception {
        staffApi.perform(put("/api/projects/"+project+"/staff-plans").contentType("application/json")
            .content(fixtures.json.writeValueAsBytes(Map.of("items",cells)))).andExpect(status().isOk());
    }
    long assignment(String job, String start, String end) throws Exception {
        return fixtures.createAssignment(assignmentApi,Map.of("prj_id",project,"dev_id",developer,"job_cd",job,
            "bgn_dt",start,"end_dt",end,"asgn_rt",BigDecimal.ONE,"asgn_se_cd","NEW","rsn_cd","CUST")).path("asgn_id").asLong();
    }
    Snapshot read(int year) { return service.list(PlanComparisonInput.query(Map.of("year",Integer.toString(year)),false)); }
    @Test void independentAggregatesDoNotMultiplyGradesAssignmentsOrMonths() throws Exception {
        plans(List.of(cell("2026-10","BE","JR","1"),cell("2026-10","BE","SR","2"),cell("2026-11","BE","JR","1")));
        assignment("BE","2026-10-01","2026-10-31");
        assignment("BE","2026-11-01","2026-11-30");
        Row row = read(2026).data().rows().get(0);
        assertThat(row.planMm()).isEqualByComparingTo("4");
        assertThat(row.actualMm()).isEqualByComparingTo("2");
        assertThat(row.gap()).isEqualTo("SHORT");
    }
    @Test void yearBoundsIncludeOnlyNovemberDecemberOfCrossYearProject() throws Exception {
        plans(List.of(cell("2026-11","BE","","1"),cell("2026-12","BE","","1"),cell("2027-01","BE","","9")));
        assignment("BE","2026-11-01","2027-02-28");
        Row row = read(2026).data().rows().get(0);
        assertThat(row.planMm()).isEqualByComparingTo("2");
        assertThat(row.actualMm()).isEqualByComparingTo("2");
        assertThat(read(2027).data().rows().get(0).actualMm()).isEqualByComparingTo("2");
    }
    @Test void cancelledResidualMonthsNeverEnterComparison() throws Exception {
        long id = assignment("BE","2026-11-01","2026-11-30");
        jdbc.update("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=?",id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asgn.asgn_mth WHERE asgn_id=?",Long.class,id)).isEqualTo(1);
        assertThat(read(2026).data().rows()).isEmpty();
    }
    @Test void actualOnlyAndPlanOnlyArePreservedButDoubleZeroIsExcluded() throws Exception {
        plans(List.of(cell("2026-10","BE","","3")));
        assignment("QA","2026-11-01","2026-11-30");
        var rows = read(2026).data().rows();
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.job_cd()).isEqualTo("BE");
            assertThat(row.actualMm()).isEqualByComparingTo("0");
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.job_cd()).isEqualTo("QA");
            assertThat(row.planMm()).isEqualByComparingTo("0");
        });
    }
    @Test void endProjectsRemainEligibleAndLostDisabledNullAndOutOfYearDoNot() throws Exception {
        plans(List.of(cell("2026-10","BE","","3")));
        jdbc.update("UPDATE prj.prj SET prj_stts_cd='END' WHERE prj_id=?",project);
        assertThat(read(2026).data().rows()).hasSize(1);
        assertThat(read(2026).projects()).hasSize(1);
        jdbc.update("UPDATE prj.prj SET prj_stts_cd='LOST' WHERE prj_id=?",project);
        assertThat(read(2026).data().rows()).isEmpty();
        jdbc.update("UPDATE prj.prj SET prj_stts_cd='RUN',use_yn='N' WHERE prj_id=?",project);
        assertThat(read(2026).data().rows()).isEmpty();
        jdbc.update("UPDATE prj.prj SET use_yn='Y',cur_end_dt=NULL,cntrct_end_dt=NULL WHERE prj_id=?",project);
        assertThat(read(2026).data().rows()).isEmpty();
        jdbc.update("UPDATE prj.prj SET cur_end_dt='2025-12-31' WHERE prj_id=?",project);
        assertThat(read(2026).data().rows()).isEmpty();
    }
    @Test void currentServerMonthSelectsLatestEffectiveStandardRatherThanSelectedYear() throws Exception {
        plans(List.of(cell("2025-10","BE","","0.15")));
        jdbc.update("INSERT INTO cmn.oprt_std(oprt_std_id,aply_bgn_ym,oprt_std_nm,num_val) VALUES('PLAN_GAP_TOLERANCE','2026-10','시험',0.20),('PLAN_GAP_TOLERANCE','2026-11','미래',0.01)");
        var result = read(2025);
        assertThat(result.tolerance()).isEqualByComparingTo("0.20");
        assertThat(result.data().rows().get(0).gap()).isEqualTo("MATCH");
    }
}
