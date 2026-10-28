package com.sicost.us5;

import static com.sicost.us5.MonthlyShortageModels.*;
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

class MonthlyShortagePersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;
    AnnotationConfigApplicationContext context;
    MonthlyShortageService service;
    MockMvc assignmentApi,staffApi;
    long project;
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures=new AssignmentTestSupport(source);
        project=fixtures.createProject();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/us5/MonthlyShortageMapper.xml"),
            new ClassPathResource("mappers/assignments/AssignmentMapper.xml"),
            new ClassPathResource("mappers/staffplans/ProjectStaffPlanMapper.xml"));
        factory.afterPropertiesSet(); var session=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        context.register(AssignmentTestSupport.Transactions.class,MonthlyShortageService.class);
        context.registerBean(MonthlyShortageMapper.class,()->session.getMapper(MonthlyShortageMapper.class));
        context.registerBean(java.time.Clock.class,()->AssignmentTestSupport.CLOCK);
        context.registerBean(PermissionService.class,PermissionService::new);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.refresh(); service=context.getBean(MonthlyShortageService.class);
        var assignmentService=new AssignmentService(session.getMapper(AssignmentMapper.class),fixtures.concurrency,
            new CurrentUser(),new PermissionService(),AssignmentTestSupport.CLOCK);
        assignmentApi=MockMvcBuilders.standaloneSetup(new AssignmentController(assignmentService,fixtures.json,AssignmentTestSupport.CLOCK))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        var staffService=new ProjectStaffPlanService(session.getMapper(ProjectStaffPlanMapper.class),new CurrentUser(),new PermissionService());
        staffApi=MockMvcBuilders.standaloneSetup(new ProjectStaffPlanController(staffService,fixtures.json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close(); if(fixtures!=null) fixtures.close(); if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    Query query() { return new Query(2026,project,"BE",0,50,"shortageMm,desc;plan_ym,asc"); }
    Map<String,Object> plan(String month,String mm,String grade) {
        return Map.of("plan_ym",month,"job_cd","BE","grd_cd",grade,"need_mm_qty",new BigDecimal(mm));
    }
    void plans(List<Map<String,Object>> rows) throws Exception {
        staffApi.perform(put("/api/projects/{id}/staff-plans",project).contentType("application/json")
            .content(fixtures.json.writeValueAsBytes(Map.of("items",rows)))).andExpect(status().isOk());
    }
    long assignment(String start,String end) throws Exception {
        var payload=new LinkedHashMap<String,Object>();
        payload.put("prj_id",project); payload.put("dev_id",fixtures.createDeveloper()); payload.put("job_cd","BE");
        payload.put("bgn_dt",start); payload.put("end_dt",end); payload.put("asgn_rt",1);
        payload.put("asgn_se_cd","NEW"); payload.put("rsn_cd","SCHED");
        return fixtures.createAssignment(assignmentApi,payload).path("asgn_id").asLong();
    }
    @Test void gradePlansAndMultipleAssignmentsAggregateIndependently() throws Exception {
        plans(List.of(plan("2026-03","2","JR"),plan("2026-03","1","SR")));
        assignment("2026-03-01","2026-03-31"); assignment("2026-03-01","2026-03-31");
        var rows=service.list(query()).rows();
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.planMm()).isEqualByComparingTo("3"); assertThat(r.actualMm()).isEqualByComparingTo("2");
            assertThat(r.shortageMm()).isEqualByComparingTo("1"); assertThat(r.job_nm()).isNotBlank();
        });
    }
    @Test void monthlyShortageRemainsWhenAnnualDifferenceCancels() throws Exception {
        plans(List.of(plan("2026-03","1.50",""),plan("2026-04","0.50","")));
        assignment("2026-03-01","2026-04-30");
        assertThat(service.rows(query())).singleElement().satisfies(r -> {
            assertThat(r.plan_ym()).isEqualTo("2026-03"); assertThat(r.shortageMm()).isEqualByComparingTo("0.50");
        });
    }
    @Test void cancelledResidualMmDoesNotFillPlan() throws Exception {
        plans(List.of(plan("2026-03","1","")));
        long id=assignment("2026-03-01","2026-03-31");
        jdbc.update("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=?",id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asgn.asgn_mth WHERE asgn_id=?",Long.class,id)).isPositive();
        assertThat(service.rows(query())).singleElement().satisfies(r -> assertThat(r.actualMm()).isEqualByComparingTo("0"));
    }
    @Test void yearBoundariesKeepJanuaryAndDecemberOnly() throws Exception {
        plans(List.of(plan("2025-12","2",""),plan("2026-01","1",""),plan("2026-12","1",""),plan("2027-01","2","")));
        assertThat(service.rows(query())).extracting(Row::plan_ym).containsExactly("2026-01","2026-12");
    }
    @Test void endedProjectIsIncludedButLostAndDisabledAreExcluded() throws Exception {
        plans(List.of(plan("2026-03","1","")));
        jdbc.update("UPDATE prj.prj SET prj_stts_cd='END' WHERE prj_id=?",project);
        assertThat(service.rows(query())).hasSize(1);
        jdbc.update("UPDATE prj.prj SET prj_stts_cd='LOST' WHERE prj_id=?",project);
        assertThat(service.rows(query())).isEmpty();
        jdbc.update("UPDATE prj.prj SET prj_stts_cd='RUN',use_yn='N' WHERE prj_id=?",project);
        assertThat(service.rows(query())).isEmpty();
    }
    @Test void unresolvedProjectPeriodIsExcluded() throws Exception {
        plans(List.of(plan("2026-03","1","")));
        jdbc.update("UPDATE prj.prj SET prfm_bgn_dt=NULL,cntrct_bgn_dt=NULL WHERE prj_id=?",project);
        assertThat(service.rows(query())).isEmpty();
    }
    @Test void actualOnlyMonthsNeverBecomeShortages() throws Exception {
        assignment("2026-03-01","2026-03-31");
        assertThat(service.rows(query())).isEmpty();
    }
}
