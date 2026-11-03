package com.sicost.opportunitiesops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.persistence.*;
import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.projects.*;
import com.sicost.assignments.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class OpportunityLifecyclePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;DriverManagerDataSource source;AnnotationConfigApplicationContext context;
    OpportunityLifecycleService lifecycle;OpportunityWinService wins;OpportunityLifecycleMapper mapper;WorkforceForecastService forecasts;
    org.springframework.test.web.servlet.MockMvc mvc;ObjectMapper json=new ObjectMapper().findAndRegisterModules();JdbcTemplate jdbc;
    @BeforeEach void database()throws Exception{
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(
            new ClassPathResource("mappers/opportunitiesops/OpportunityLifecycleMapper.xml"),new ClassPathResource("mappers/opportunitiesops/OpportunityForecastMapper.xml"),
            new ClassPathResource("mappers/projects/ProjectMapper.xml"),new ClassPathResource("mappers/assignments/AssignmentMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"));factory.afterPropertiesSet();var session=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,OpportunityLifecycleService.class,OpportunityWinService.class,OpportunityQuoteVerifier.class,
            OpportunityExportService.class,WorkforceForecastService.class,ExcelExportService.class,ProjectService.class,AssignmentService.class,AssignmentConcurrencyService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(ObjectMapper.class,()->json);context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(OpportunityLifecycleMapper.class,()->session.getMapper(OpportunityLifecycleMapper.class));context.registerBean(OpportunityForecastMapper.class,()->session.getMapper(OpportunityForecastMapper.class));
        context.registerBean(ProjectMapper.class,()->session.getMapper(ProjectMapper.class));context.registerBean(AssignmentMapper.class,()->session.getMapper(AssignmentMapper.class));
        context.registerBean(AssignmentConcurrencyMapper.class,()->session.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(MonthlyProfitReader.class,()->month->new MonthlyProfitReader.Month(month,null,null,List.of("FUTURE_ACTUAL")));
        context.refresh();lifecycle=context.getBean(OpportunityLifecycleService.class);wins=context.getBean(OpportunityWinService.class);mapper=context.getBean(OpportunityLifecycleMapper.class);forecasts=context.getBean(WorkforceForecastService.class);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new OpportunityLifecycleController(lifecycle,wins,forecasts,context.getBean(OpportunityExportService.class),json)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        jdbc.update("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(91,'C-OWN','CUST','검증 고객','123-45-67890')");
        jdbc.update("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,duty_se_cd,main_job_cd,grd_cd) VALUES(91,'K-OWN','검증 인력','FT','DIRECT','BE','SR')");
        jdbc.update("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,duty_se_cd,slry_amt) VALUES(91,'2026-10','FT','DIRECT',8000000)");
        jdbc.update("INSERT INTO bsn.oppt(oppt_id,oppt_no,oppt_nm,cust_ptnr_id,bsn_part_id,cntrct_ty_cd,expc_bgn_ym,expc_mnth_qty,expc_amt,quot_aprv_stts_cd,expc_sls_amt,expc_cst_amt,expc_mrgn_rt) VALUES(91,'O-91','검증 기회',91,1,'MO','2026-11',4,90000000,'PENDING',80000000,64000000,20)");
        jdbc.update("INSERT INTO bsn.oppt_need(need_id,oppt_id,job_cd,grd_cd,psn_cnt,bgn_ym,mnth_qty,mth_mm_qty,expc_untprc_amt,psn_mth_cst_amt) VALUES(91,91,'BE','SR',2,'2026-11',4,1,10000000,8000000)");
        jdbc.update("INSERT INTO asgn.tmpr_asgn(need_id,dev_id,bgn_ym,end_ym,mm_qty) VALUES(91,91,'2026-11','2027-02',1)");
    }
    @AfterEach void cleanup()throws Exception{if(context!=null)context.close();if(source==null)return;try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s,database);}}
    long count(String sql){return jdbc.queryForObject(sql,Long.class);}
    String value(String sql){return jdbc.queryForObject(sql,String.class);}
    String snapshot(){return value("SELECT json_build_array((SELECT json_agg(o ORDER BY oppt_id) FROM bsn.oppt o),(SELECT json_agg(n ORDER BY need_id) FROM bsn.oppt_need n),(SELECT json_agg(t ORDER BY tmpr_asgn_id) FROM asgn.tmpr_asgn t),(SELECT json_agg(h ORDER BY aprv_hist_id) FROM bsn.quot_aprv_hist h),(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),(SELECT json_agg(p ORDER BY prj_id) FROM prj.prj p),(SELECT json_agg(m ORDER BY pl_ym,pl_src_cd) FROM fin.mth_pl m))::text");}
    Map<String,Object> project(){return Map.of("prj_nm","검증 프로젝트","cust_ptnr_id",91,"bsn_part_id",1,"cntrct_ty_cd","MO","prj_stts_cd","RUN","cntrct_bgn_dt","2026-11-01","cntrct_end_dt","2027-02-28","init_cntrct_amt",80000000);}
    @Test void stageAndAnonymousApprovalReadBackActualRows()throws Exception{
        mvc.perform(post("/api/opportunities/91/stage").contentType("application/json").content("{\"bsn_stg_cd\":\"PROP\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.win_prblt_rt").value(30));
        assertThat(value("SELECT bsn_stg_cd FROM bsn.oppt WHERE oppt_id=91")).isEqualTo("PROP");
        mvc.perform(post("/api/opportunities/91/approve").contentType("application/json").content("{}")).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM bsn.quot_aprv_hist WHERE oppt_id=91 AND aprv_prcs_cd='APPROVE' AND prcs_user_id IS NULL")).isEqualTo(1);
        assertThat(value("SELECT quot_aprv_stts_cd FROM bsn.oppt WHERE oppt_id=91")).isEqualTo("APPROVED");
    }
    @ParameterizedTest @ValueSource(strings={"lose","disable"})void closingRetainsNeedsAndReleasesAllTemps(String action)throws Exception{
        mvc.perform(post("/api/opportunities/91/"+action).contentType("application/json").content("{\"reason\":\"사업 취소\"}")).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM bsn.oppt_need WHERE need_id=91")).isEqualTo(1);assertThat(value("SELECT tmpr_asgn_stts_cd FROM asgn.tmpr_asgn WHERE need_id=91")).isEqualTo("RELEASED");
        assertThat(mapper.exportRows(OpportunityLifecycleInput.export(Map.of()))).isEmpty();assertThat(forecasts.opportunityForecast(Map.of()).kpi().opportunity_total()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"approve","reject"})void failedApprovalHistoryRollsBackBothSides(String action)throws Exception{
        String before=snapshot();jdbc.execute("ALTER TABLE bsn.quot_aprv_hist ADD CONSTRAINT refuse_append CHECK(false) NOT VALID");
        mvc.perform(post("/api/opportunities/91/"+action).contentType("application/json").content(action.equals("approve")?"{}":"{\"reason\":\"재검토\"}")).andExpect(status().isInternalServerError());assertThat(snapshot()).isEqualTo(before);
    }
    @Test void failedReleaseRollsBackLoss()throws Exception{String before=snapshot();jdbc.execute("ALTER TABLE asgn.tmpr_asgn ADD CONSTRAINT refuse_release CHECK(tmpr_asgn_stts_cd='ACTIVE') NOT VALID");mvc.perform(post("/api/opportunities/91/lose").contentType("application/json").content("{\"reason\":\"취소\"}")).andExpect(status().isInternalServerError());assertThat(snapshot()).isEqualTo(before);}
    @Test void winCreatesExactlyOneProjectAndSourcePlansAndAssignment()throws Exception{
        jdbc.update("UPDATE bsn.oppt SET quot_aprv_stts_cd='NONE' WHERE oppt_id=91");
        mvc.perform(post("/api/opportunities/91/win").contentType("application/json").content(json.writeValueAsString(Map.of("project",project())))).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM prj.prj WHERE oppt_id=91 AND init_cntrct_amt=80000000")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM prj.psn_plan WHERE src_need_id=91 AND need_psn_cnt=2 AND need_mm_qty=2 AND expc_mth_cst_amt=16000000")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM asgn.tmpr_asgn WHERE need_id=91 AND tmpr_asgn_stts_cd='CONVERTED' AND asgn_id IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM asgn.asgn_chg_hist WHERE asgn_chg_ty_cd='START'")).isEqualTo(1);
        mvc.perform(post("/api/opportunities/91/win").contentType("application/json").content(json.writeValueAsString(Map.of("project",project())))).andExpect(status().isConflict());
    }
    @Test void conversionFailureRollsBackNewProjectAndPlans()throws Exception{
        jdbc.update("UPDATE bsn.oppt SET quot_aprv_stts_cd='NONE' WHERE oppt_id=91");jdbc.update("UPDATE asgn.tmpr_asgn SET bgn_ym='2027-03',end_ym='2027-04' WHERE need_id=91");String before=snapshot();
        mvc.perform(post("/api/opportunities/91/win").contentType("application/json").content(json.writeValueAsString(Map.of("project",project())))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSIGNMENT_CONFLICT"));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void concurrentWinsCreateOneProjectAndNoDuplicateConversions()throws Exception{
        jdbc.update("UPDATE bsn.oppt SET quot_aprv_stts_cd='NONE' WHERE oppt_id=91");String payload=json.writeValueAsString(Map.of("project",project()));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> call=()->{start.await();return mvc.perform(post("/api/opportunities/91/win").contentType("application/json").content(payload)).andReturn().getResponse().getStatus();};
            var first=executor.submit(call);var second=executor.submit(call);start.countDown();
            assertThat(List.of(first.get(20,java.util.concurrent.TimeUnit.SECONDS),second.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(count("SELECT count(*) FROM prj.prj WHERE oppt_id=91")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM asgn.asgn_chg_hist WHERE asgn_chg_ty_cd='START'")).isEqualTo(1);
        } finally {executor.shutdownNow();}
    }
    @Test void exportAndForecastNeverChangeBusinessOrClosedSnapshots()throws Exception{
        String before=snapshot();mvc.perform(get("/api/opportunities/forecast").param("job","BE").param("grade","SR")).andExpect(status().isOk());mvc.perform(get("/api/opportunities/export").param("q","검증")).andExpect(status().isOk());assertThat(snapshot()).isEqualTo(before);assertThat(count("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-29'")).isEqualTo(1);
    }
}
