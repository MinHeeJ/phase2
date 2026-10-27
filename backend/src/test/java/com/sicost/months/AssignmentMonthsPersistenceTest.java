package com.sicost.months;

import static com.sicost.months.AssignmentMonthsModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import com.sicost.apiops.ProjectBoardMapper;
import com.sicost.apiops.ProjectCostService;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class AssignmentMonthsPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    AssignmentTestSupport actors;
    AssignmentMonthsService service;
    AssignmentMonthsMapper mapper;
    ObjectMapper json;
    MockMvc mvc;
    long project, developer, assignment;

    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");
        password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        actors=new AssignmentTestSupport(source);
        project=actors.createProject();
        developer=actors.createDeveloper();
        assignment=createAssignment(project,developer);
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,auto_mm_qty,mm_qty,mnl_yn) VALUES("+assignment+",'2026-10',0.52,0.52,'N'),("+assignment+",'2026-11',0.52,0.52,'N')");
        var factory=new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/months/AssignmentMonthsMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"),
            new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"));
        factory.afterPropertiesSet();
        var session=new SqlSessionTemplate(factory.getObject());
        mapper=session.getMapper(AssignmentMonthsMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,AssignmentMonthsService.class,AssignmentConcurrencyService.class,
            CurrentUser.class,PermissionService.class,ProjectCostService.class);
        context.registerBean(AssignmentMonthsMapper.class,()->mapper);
        context.registerBean(AssignmentConcurrencyMapper.class,()->session.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(ProjectBoardMapper.class,()->session.getMapper(ProjectBoardMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.refresh();
        service=context.getBean(AssignmentMonthsService.class);
        json=actors.json;
        mvc=MockMvcBuilders.standaloneSetup(new AssignmentMonthsController(service,json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close();
        if(actors!=null) actors.close();
        if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement()) { s.execute(text); }
    }
    String value(String text) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)) {
            r.next(); return r.getString(1);
        }
    }
    long createAssignment(long prj,long dev) throws Exception {
        return Long.parseLong(value("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,plan_end_dt,asgn_rt,created_at) "
            +"VALUES('A-"+UUID.randomUUID().toString().substring(0,12)+"',"+prj+","+dev+",'BE','2026-09-01','2026-11-30','2026-11-30',0.52,'2026-10-01 09:00:00.123456') RETURNING asgn_id"));
    }
    MonthsInput input(String month,String quantity) {
        return new MonthsInput(service.get(assignment).updated_at(),List.of(new MonthInput(month,new BigDecimal(quantity))));
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('assignments',(SELECT json_agg(a ORDER BY asgn_id) FROM asgn.asgn a),"
            +"'months',(SELECT json_agg(m ORDER BY asgn_id,work_ym) FROM asgn.asgn_mth m),"
            +"'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),"
            +"'rates',(SELECT json_agg(r ORDER BY asgn_id,aply_bgn_dt) FROM asgn.untprc_hist r))::text");
    }
    @Test void anonymousReadManualMonthAndLegacyTimestampWithoutWritingMissingClosedMonth() throws Exception {
        sql("UPDATE asgn.asgn_mth SET mm_qty=0.50,mnl_yn='Y' WHERE asgn_id="+assignment+" AND work_ym='2026-10'");
        String before=snapshot();
        mvc.perform(get("/api/assignments/{id}/months",assignment)).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.updated_at").value("2026-10-01T09:00:00.123456"))
            .andExpect(jsonPath("$.data.items[0].work_ym").value("2026-09"))
            .andExpect(jsonPath("$.data.items[0].closed_yn").value("Y"))
            .andExpect(jsonPath("$.data.items[1].auto_mm_qty").value(0.52))
            .andExpect(jsonPath("$.data.items[1].mm_qty").value(0.50))
            .andExpect(jsonPath("$.data.items[1].mnl_yn").value("Y"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(value("SELECT count(*) FROM asgn.asgn_mth WHERE work_ym='2026-09' AND asgn_id="+assignment)).isEqualTo("0");
    }
    @Test void nonexistentAssignmentCannotExposeAnotherAssignmentsMonths() throws Exception {
        mvc.perform(get("/api/assignments/{id}/months",Long.MAX_VALUE)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void readingMissingClosedMonthNeverMaterializesIt() throws Exception {
        String before=snapshot();
        service.get(assignment);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(value("SELECT count(*) FROM asgn.asgn_mth WHERE asgn_id="+assignment+" AND work_ym='2026-09'"))
            .isEqualTo("0");
    }
    @Test void mmSaveChangesAnnualSumAndExistingProjectCostReadWithoutCostWrites() throws Exception {
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES("+developer+",'2026-10','FT',1000000)");
        var costs=context.getBean(ProjectCostService.class);
        long beforeCost=costs.calculate(project,"2026-10").cumulative_cost_amt();
        var beforeYear=new BigDecimal(value("SELECT sum(mm_qty) FROM asgn.asgn_mth WHERE asgn_id="+assignment+" AND work_ym LIKE '2026-%'"));
        String snapshots=value("SELECT json_agg(c ORDER BY mth_cst_id)::text FROM cst.mth_cst c");
        service.save(assignment,input("2026-10","0.50"));
        assertThat(costs.calculate(project,"2026-10").cumulative_cost_amt()).isLessThan(beforeCost);
        var afterYear=new BigDecimal(value("SELECT sum(mm_qty) FROM asgn.asgn_mth WHERE asgn_id="+assignment+" AND work_ym LIKE '2026-%'"));
        assertThat(afterYear).isEqualByComparingTo(beforeYear.subtract(new BigDecimal("0.52")).add(new BigDecimal("0.50")));
        assertThat(value("SELECT json_agg(c ORDER BY mth_cst_id)::text FROM cst.mth_cst c")).isEqualTo(snapshots);
        assertThat(value("SELECT count(*) FROM cst.cst_aloc")).isEqualTo("0");
    }
    @Test void personTotalsIncludeOtherProjectsButExcludeCancelledAssignments() throws Exception {
        long other=createAssignment(actors.createProject(),developer);
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,auto_mm_qty,mm_qty) VALUES("+assignment+",'2026-09',0.52,0.52),("+other+",'2026-09',0.68,0.68)");
        var row=service.get(assignment).items().get(0);
        assertThat(row.person_month_mm_qty()).isEqualByComparingTo("1.20");
        assertThat(row.closed_yn()).isEqualTo("Y");
        assertThat(service.get(assignment).warnings()).anyMatch(w->w.code().equals("OVER_MM"));
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id="+other);
        assertThat(service.get(assignment).items().get(0).person_month_mm_qty()).isEqualByComparingTo("0.52");
    }
    @Test void anonymousPutPersistsOneMonthOneAuditAndAdvancesParentThenRestoresAuto() throws Exception {
        var body=input("2026-10","0.50");
        mvc.perform(put("/api/assignments/{id}/months",assignment).contentType("application/json")
            .content(json.writeValueAsBytes(body))).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items[1].mm_qty").value(0.50))
            .andExpect(jsonPath("$.data.items[1].mnl_yn").value("Y"));
        assertThat(service.get(assignment).updated_at()).isAfter(body.updated_at());
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='asgn.asgn_mth' AND obj_rec_id='"+assignment+"' AND clmn_nm='2026-10' AND bfr_val='0.52' AND aft_val='0.50'")).isEqualTo("1");
        assertThat(value("SELECT mm_qty FROM asgn.asgn_mth WHERE asgn_id="+assignment+" AND work_ym='2026-11'")).isEqualTo("0.52");
        service.save(assignment,input("2026-10","0.52"));
        assertThat(service.get(assignment).items().get(1).mnl_yn()).isEqualTo("N");
    }
    @Test void closedMonthRequestRollsBackAllMonths() throws Exception {
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,auto_mm_qty,mm_qty) VALUES("+assignment+",'2026-09',0.52,0.52)");
        var before=snapshot();
        var body=new MonthsInput(service.get(assignment).updated_at(),List.of(
            new MonthInput("2026-10",new BigDecimal("0.50")),new MonthInput("2026-09",BigDecimal.ZERO)));
        mvc.perform(put("/api/assignments/{id}/months",assignment).contentType("application/json")
            .content(json.writeValueAsBytes(body))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void sameTimestampSecondSaveIsRejectedWithoutFurtherWrites() throws Exception {
        var body=input("2026-10","0.50");
        service.save(assignment,body);
        var before=snapshot();
        assertThatThrownBy(()->service.save(assignment,body)).isInstanceOfSatisfying(ApiException.class,
            ex->assertThat(ex.code()).isEqualTo("STALE_ASSIGNMENT"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void auditFailureRollsBackQuantityManualFlagParentAndAllOtherMonths() throws Exception {
        var body=input("2026-10","0.50");
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_month_history CHECK(tbl_nm <> 'asgn.asgn_mth') NOT VALID");
        String before=snapshot();
        assertThatThrownBy(()->service.save(assignment,body)).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void roundThenZeroArePersistedAndUnchangedMonthsHaveNoAudit() throws Exception {
        service.save(assignment,input("2026-10","0.523"));
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='asgn.asgn_mth'")).isEqualTo("0");
        service.save(assignment,input("2026-10","0"));
        assertThat(service.get(assignment).items().get(1).mm_qty()).isEqualByComparingTo("0");
        assertThat(service.get(assignment).items().get(1).mnl_yn()).isEqualTo("Y");
    }
    @ParameterizedTest @ValueSource(strings={"-0.1","1.20"})
    void invalidQuantityHasNoPersistenceEffect(String quantity) throws Exception {
        var body=input("2026-10",quantity);
        var before=snapshot();
        mvc.perform(put("/api/assignments/{id}/months",assignment).contentType("application/json")
            .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("mm_qty"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void overMmIsWarningAndDoesNotBlockSaving() throws Exception {
        long other=createAssignment(actors.createProject(),developer);
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,auto_mm_qty,mm_qty) VALUES("+other+",'2026-10',0.70,0.70)");
        var saved=service.save(assignment,input("2026-10","0.50"));
        assertThat(saved.items().get(1).person_month_mm_qty()).isEqualByComparingTo("1.20");
        assertThat(saved.warnings()).anyMatch(w->w.code().equals("OVER_MM")&&w.work_ym().equals("2026-10"));
    }
    @Test void concurrentSameTokenAllowsOnlyOneCommit() throws Exception {
        var body=input("2026-10","0.50");
        var pool=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        Callable<String> action=()->{
            start.await();
            try { service.save(assignment,body); return "OK"; }
            catch(ApiException ex) { return ex.code(); }
        };
        try {
            var first=pool.submit(action);
            var second=pool.submit(action);
            start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("OK","STALE_ASSIGNMENT");
            assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='asgn.asgn_mth'")).isEqualTo("1");
        } finally { pool.shutdownNow(); }
    }
}
