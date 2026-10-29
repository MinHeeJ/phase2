package com.sicost.apiops;

import static com.sicost.apiops.ProjectBoardModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectBoardPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ProjectBoardService service;
    ProjectCostService costs;
    ProjectBoardMapper mapper;
    MockMvc mvc;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"),
            new ClassPathResource("mappers/costallocation/CostAllocationMapper.xml"));
        factory.afterPropertiesSet(); mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ProjectBoardMapper.class);
        context = new AnnotationConfigApplicationContext();
        com.sicost.common.CostAllocationTestSupport.register(context, new SqlSessionTemplate(factory.getObject()));
        context.register(Transactions.class, ProjectBoardService.class, ProgressParameterService.class, ProjectCostService.class, PermissionService.class);
        context.registerBean(ProjectBoardMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.refresh(); service = context.getBean(ProjectBoardService.class); costs = context.getBean(ProjectCostService.class);
        var json = context.getBean(ObjectMapper.class).copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new ProjectBoardController(service, context.getBean(ProgressParameterService.class)))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        for (var p : ProjectBoardApiTest.params()) sql("INSERT INTO cmn.oprt_std(oprt_std_id,aply_bgn_ym,oprt_std_nm,num_val) VALUES('" + p.oprt_std_id() + "','2026-10','시험'," + p.num_val() + ") ON CONFLICT(oprt_std_id,aply_bgn_ym) DO UPDATE SET num_val=excluded.num_val");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(90001,'K-90001','시험 개발자','FT')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cur_cntrct_amt,prfm_bgn_dt) VALUES(90001,'P-26901','진행','TK','RUN',500000000,'2026-10-08'),(90002,'P-26902','종료','TK','END',500000000,NULL),(90003,'P-26903','미투입 예정','TK','PLAN',500000000,NULL),(90004,'P-26904','투입 예정','MO','PLAN',NULL,NULL),(90005,'P-26905','보류','MO','HOLD',NULL,NULL),(90006,'P-26906','미사용','MO','RUN',NULL,NULL)");
        sql("UPDATE prj.prj SET use_yn='N' WHERE prj_id=90006");
        sql("UPDATE prj.prj SET trgt_cst_amt=400000000 WHERE prj_id BETWEEN 90001 AND 90006");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(90001,'A-90001',90001,90001,'BE','2026-01-01','2026-12-31'),(90002,'A-90002',90002,90001,'BE','2026-01-01','2026-12-31'),(90003,'A-90003',90004,90001,'BE','2026-10-23','2026-10-23')");
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    void sql(String text) throws Exception { try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(text); } }
    String value(String text) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(text)) { r.next(); return r.getString(1); }
    }
    @Test void targetPopulationUsesActiveInclusiveAssignmentsAndNoYearRestriction() throws Exception {
        var board = service.get(Map.of());
        assertThat(board.items()).extracting(BoardRow::prj_id).containsExactly(90001L, 90004L);
        assertThat(board.summary().stale_project_count()).isEqualTo(1);
        assertThat(board.stale_projects()).extracting(StaleProject::memo_elapsed_days).containsExactly(15);
        mvc.perform(get("/api/project-board").param("type", "MO")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.summary.project_count").value(2));
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=90003");
        assertThat(service.get(Map.of()).items()).extracting(BoardRow::prj_id).containsExactly(90001L);
    }
    @Test void costUsesAllProjectDenominatorAndFloorsAggregatedPersonMonth() throws Exception {
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90001,'2026-10',1.00),(90002,'2026-10',0.50)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(90001,'2026-10','FT',4970000)");
        assertThat(costs.calculate(90001, "2026-10").cumulative_cost_amt()).isEqualTo(3313333);
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(90004,'A-90004',90001,90001,'BE','2026-01-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90004,'2026-10',0.50)");
        assertThat(costs.calculate(90001, "2026-10").cumulative_cost_amt()).isEqualTo(3727500);
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=90004");
        assertThat(costs.calculate(90001, "2026-10").cumulative_cost_amt()).isEqualTo(3313333);
    }
    @Test void closedMonthReplacesLaborAndExpensesMissingSalaryAndFutureMonthsAreHandled() throws Exception {
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90001,'2026-09',1),(90001,'2026-10',1),(90001,'2026-11',1)");
        sql("INSERT INTO cst.drct_expns(expns_ym,prj_id,expns_item_cd,expns_amt,vat_amt) VALUES('2026-09',90001,'TRIP',9999,999),('2026-10',90001,'TRIP',1000,100),('2026-11',90001,'TRIP',777777,777)");
        sql("INSERT INTO cst.cst_aloc(cst_ym,dev_id,prj_id,aloc_ty_cd,lbr_cst_amt,drct_expns_amt) VALUES('2026-09',90001,90001,'PROJECT',2000,500)");
        var cost = costs.calculate(90001, "2026-10");
        assertThat(cost.cumulative_cost_amt()).isEqualTo(3500);
        assertThat(cost.missing_salary_months()).containsExactly("2026-10");
        sql("UPDATE fin.mth_cls SET cls_stts_cd='OPEN' WHERE cls_ym='2026-09'");
        assertThat(costs.calculate(90001, "2026-10").cumulative_cost_amt()).isEqualTo(10999);
        assertThat(costs.calculate(90001, "2026-10").missing_salary_months()).containsExactly("2026-09", "2026-10");
    }
    @Test void paramsSelectLatestAtRequestedMonthAndMissingErrorLeavesDatabaseUnchanged() throws Exception {
        sql("INSERT INTO cmn.oprt_std(oprt_std_id,aply_bgn_ym,oprt_std_nm,num_val) VALUES('BUDGET_RATE','2026-11','예산',75)");
        assertThat(mapper.params("2026-10").stream().filter(p -> p.oprt_std_id().equals("BUDGET_RATE")).findFirst().orElseThrow().num_val()).isEqualByComparingTo("80");
        assertThat(mapper.params("2026-11").stream().filter(p -> p.oprt_std_id().equals("BUDGET_RATE")).findFirst().orElseThrow().num_val()).isEqualByComparingTo("75");
        sql("DELETE FROM cmn.oprt_std WHERE oprt_std_id='SIG_COST_WARN'");
        String before = value("SELECT json_agg(p ORDER BY prj_id)::text FROM prj.prj p");
        mvc.perform(get("/api/project-board")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
        assertThat(value("SELECT json_agg(p ORDER BY prj_id)::text FROM prj.prj p")).isEqualTo(before);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo("0");
    }
    @Test void readsRecalculateLatestMemoAndDoNotWriteSourcesOrHistory() throws Exception {
        sql("INSERT INTO prj.phs(prj_id,phs_seq,phs_nm,wght_rt,plan_bgn_dt,plan_end_dt) VALUES(90001,1,'분석',50.5,'2026-10-01','2026-10-02'),(90001,2,'개발',49.5,NULL,NULL)");
        sql("INSERT INTO prj.wkly_memo(prj_id,wrt_dt,actl_prgs_rt) VALUES(90001,'2026-10-08',42.5),(90004,'2026-10-09',NULL)");
        sql("INSERT INTO cst.drct_expns(expns_ym,prj_id,expns_item_cd,expns_amt) VALUES('2026-10',90001,'TRIP',230000000)");
        var first = service.get(Map.of()).items().stream().filter(p -> p.prj_id() == 90001).findFirst().orElseThrow();
        assertThat(first.light()).isEqualTo("위험"); assertThat(first.actl_prgs_rt()).isEqualByComparingTo("42.5");
        assertThat(first.cost_progress_diff_rt()).isEqualByComparingTo("15.0");
        assertThat(service.get(Map.of("light", "위험")).summary()).isEqualTo(service.get(Map.of()).summary());
        sql("INSERT INTO prj.wkly_memo(prj_id,wrt_dt,actl_prgs_rt) VALUES(90001,'2026-10-23',60)");
        assertThat(service.get(Map.of()).summary().stale_project_count()).isZero();
        assertThat(value("SELECT count(*) FROM prj.phs WHERE plan_bgn_dt IS NULL")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo("0");
    }
}
