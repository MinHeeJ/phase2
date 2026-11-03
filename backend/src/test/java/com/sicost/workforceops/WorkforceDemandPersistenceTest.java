package com.sicost.workforceops;

import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.WorkforcePlanningAssertions;
import com.sicost.common.api.*;
import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.opportunitiesops.*;
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

class WorkforceDemandPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;
    AnnotationConfigApplicationContext context;
    MockMvc mvc;
    long developer;
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures=new AssignmentTestSupport(source);developer=fixtures.createDeveloper();
        jdbc.update("UPDATE mst.dev SET grd_cd='S' WHERE dev_id=?",developer);
        jdbc.update("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(?,'2026-09','FT',10000000)",developer);
        jdbc.update("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-09','CLOSED') ON CONFLICT(cls_ym) DO NOTHING");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/opportunitiesops/OpportunityForecastMapper.xml"));
        factory.afterPropertiesSet();var session=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();context.register(AssignmentTestSupport.Transactions.class,WorkforceForecastService.class);
        context.registerBean(OpportunityForecastMapper.class,()->session.getMapper(OpportunityForecastMapper.class));
        context.registerBean(MonthlyProfitReader.class,()->month->new MonthlyProfitReader.Month(month,null,null,List.of("FUTURE_ACTUAL")));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->AssignmentTestSupport.CLOCK);context.refresh();
        mvc=MockMvcBuilders.standaloneSetup(new WorkforceDemandController(context.getBean(WorkforceForecastService.class),AssignmentTestSupport.CLOCK))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void cleanup() throws Exception {
        if(context!=null)context.close();if(fixtures!=null)fixtures.close();if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    private com.fasterxml.jackson.databind.JsonNode read() throws Exception {
        var result=mvc.perform(get("/api/workforce/forecast").param("from","2026-11").param("months","1").param("job","BE").param("grade","S"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
        return fixtures.json.readTree(result.getResponse().getContentAsByteArray()).get("data");
    }
    @Test void realMapperMaterializesFilteredSourcesAndLeavesSnapshotsAndHistoryUntouched() throws Exception {
        long project=fixtures.createProject();
        jdbc.update("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(?,'2026-10','FL',90000000)",developer);
        jdbc.update("INSERT INTO prj.psn_plan(prj_id,plan_ym,job_cd,grd_cd,need_mm_qty) VALUES(?,'2026-11','BE','S',1.41)",project);
        jdbc.update("INSERT INTO mst.dev_work_stts_hist(dev_id,work_stts_cd,bgn_dt,end_dt) VALUES(?,'LEAVE','2026-11-01','2026-11-15')",developer);
        var closed=WorkforcePlanningAssertions.captureClosedSources(jdbc);var before=businessSources();
        var first=read();var second=read();
        WorkforcePlanningAssertions.assertForecastItemsEqual(first,second);
        var row=first.get("items").get(0);
        assertThat(first.get("total").asInt()).isEqualTo(1);
        assertThat(row.get("available_mm").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(row.get("demand_mm").decimalValue()).isEqualByComparingTo("1.41");
        assertThat(row.get("idle_cost").asLong()).isEqualTo(5000000);
        var shared=context.getBean(WorkforceForecastService.class).opportunityForecast(Map.of("from","2026-11","months","1","job","BE","grade","S")).workforce();
        WorkforcePlanningAssertions.assertForecastItemsEqual(first,fixtures.json.readTree(fixtures.json.writeValueAsBytes(shared)));
        assertThat(businessSources()).isEqualTo(before);closed.assertUnchanged(jdbc);
    }
    @Test void invalidAndEmptyQueriesNeverWriteBusinessRows() throws Exception {
        var before=businessSources();var closed=WorkforcePlanningAssertions.captureClosedSources(jdbc);
        mvc.perform(get("/api/workforce/forecast").param("from","2026-13")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("from")).andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());
        mvc.perform(get("/api/workforce/forecast").param("sort","salary,asc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/workforce/forecast").param("year","2025")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0));
        assertThat(businessSources()).isEqualTo(before);closed.assertUnchanged(jdbc);
    }
    private Map<String,String> businessSources(){
        var rows=new LinkedHashMap<String,String>();
        for(String table:List.of("mst.dev","mst.dev_work_stts_hist","asgn.asgn","asgn.asgn_mth","asgn.tmpr_asgn",
            "prj.psn_plan","bsn.oppt","bsn.oppt_need","cst.mth_cst","sys.data_chg_hist","sys.acs_hist","asgn.asgn_chg_hist"))
            rows.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+table+" t",String.class));
        return rows;
    }
}
