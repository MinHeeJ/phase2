package com.sicost.assignments;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class AssignmentPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    AssignmentTestSupport fixtures;
    MockMvc mvc;
    ObjectMapper json;
    long project,developer;
    @BeforeEach void setup() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.create(s,database); }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures=new AssignmentTestSupport(source); json=fixtures.json;
        project=fixtures.createProject(); developer=fixtures.createDeveloper();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/assignments/AssignmentMapper.xml"),new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"));
        factory.afterPropertiesSet(); var session=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,AssignmentService.class,AssignmentConcurrencyService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(AssignmentMapper.class,()->session.getMapper(AssignmentMapper.class));
        context.registerBean(AssignmentConcurrencyMapper.class,()->session.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->AssignmentTestSupport.CLOCK); context.registerBean(ObjectMapper.class,()->json);
        context.refresh();
        mvc=MockMvcBuilders.standaloneSetup(new AssignmentController(context.getBean(AssignmentService.class),json,AssignmentTestSupport.CLOCK))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void cleanup() throws Exception {
        if(context!=null) context.close(); if(fixtures!=null) fixtures.close(); if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.drop(s,database); }
    }
    Map<String,Object> payload(String begin,String end) {
        var p=new LinkedHashMap<String,Object>(); p.put("prj_id",project); p.put("dev_id",developer); p.put("job_cd","BE");
        p.put("bgn_dt",begin);p.put("end_dt",end);p.put("asgn_rt",1);p.put("asgn_se_cd","NEW");p.put("rsn_cd","SCHED"); return p;
    }
    JsonNode create(String begin,String end) throws Exception { return fixtures.createAssignment(mvc,payload(begin,end)); }
    void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);} }
    String value(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);} }
    String snapshot() throws Exception { return value("SELECT json_build_object('a',(SELECT json_agg(a ORDER BY asgn_id) FROM asgn.asgn a),'m',(SELECT json_agg(m ORDER BY asgn_id,work_ym) FROM asgn.asgn_mth m),'r',(SELECT json_agg(r ORDER BY asgn_id,aply_bgn_dt) FROM asgn.untprc_hist r),'h',(SELECT json_agg(h ORDER BY asgn_chg_id) FROM asgn.asgn_chg_hist h))::text"); }
    @Test void anonymousCreateReadAndFilteredListPersistExactMonthsAndHistory() throws Exception {
        sql("UPDATE prj.prj SET cntrct_ty_cd='MO' WHERE prj_id="+project);
        var p=payload("2026-10-16","2026-12-31");p.put("bill_untprc_amt",7000000);p.put("untprc_base_cd","MONTH");
        var row=fixtures.createAssignment(mvc,p); long id=row.path("asgn_id").asLong();
        assertThat(row.path("asgn_no").asText()).isEqualTo("A-0001");
        assertThat(value("SELECT mm_qty FROM asgn.asgn_mth WHERE asgn_id="+id+" AND work_ym='2026-10'")).isEqualTo("0.52");
        assertThat(value("SELECT bill_untprc_amt FROM asgn.untprc_hist WHERE asgn_id="+id)).isEqualTo("7000000");
        assertThat(value("SELECT count(*) FROM asgn.asgn_chg_hist WHERE asgn_id="+id+" AND asgn_chg_ty_cd='START' AND aply_dt='2026-10-16'")).isEqualTo("1");
        String before=snapshot();
        mvc.perform(get("/api/assignments").param("year","2026").param("projectId",String.valueOf(project)).param("job","BE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].year_mm_qty").value(2.52)).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/assignments").param("year","2026").param("projectId",String.valueOf(project)).param("job","FE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/assignments/{id}",id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.months.length()").value(3));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void warningsAreReevaluatedAndCancelledRowsExcludedFromPersonTotals() throws Exception {
        sql("UPDATE prj.prj SET prj_stts_cd='PLAN' WHERE prj_id="+project);
        var a=create("2026-10-16","2026-10-31"); long id=a.path("asgn_id").asLong();
        mvc.perform(get("/api/assignments").param("year","2026").param("warning","PRE_CONTRACT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        sql("UPDATE prj.prj SET prj_stts_cd='RUN' WHERE prj_id="+project);
        mvc.perform(get("/api/assignments").param("year","2026").param("warning","PRE_CONTRACT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id="+id);
        String before=snapshot();
        mvc.perform(get("/api/assignments").param("year","2026")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/assignments/{id}",id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.asgn_stts_cd").value("CANCELLED"))
            .andExpect(jsonPath("$.data.person_month_mm_qty").value(0));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void closedMonthHasNoFinancialWritesAndOriginalStartDateIsPreserved() throws Exception {
        var row=create("2026-09-01","2026-10-31");long id=row.path("asgn_id").asLong();
        assertThat(value("SELECT count(*) FROM asgn.asgn_mth WHERE asgn_id="+id+" AND work_ym='2026-09'")).isEqualTo("0");
        assertThat(value("SELECT aply_bgn_dt FROM asgn.untprc_hist WHERE asgn_id="+id)).isEqualTo("2026-10-01");
        assertThat(value("SELECT aply_dt FROM asgn.asgn_chg_hist WHERE asgn_id="+id)).isEqualTo("2026-09-01");
    }
    @Test void monthFailureRollsBackParentRateMonthsAndStart() throws Exception {
        sql("ALTER TABLE asgn.asgn_mth ADD CONSTRAINT reject_assignment_mm CHECK(false) NOT VALID");String before=snapshot();
        mvc.perform(post("/api/assignments").contentType("application/json").content(json.writeValueAsString(payload("2026-10-16","2026-12-31"))))
            .andExpect(status().is5xxServerError());assertThat(snapshot()).isEqualTo(before);
    }
    @Test void historyFailureAlsoRollsBackFinancialRows() throws Exception {
        sql("ALTER TABLE asgn.asgn_chg_hist ADD CONSTRAINT reject_assignment_history CHECK(false) NOT VALID");String before=snapshot();
        mvc.perform(post("/api/assignments").contentType("application/json").content(json.writeValueAsString(payload("2026-10-16","2026-12-31"))))
            .andExpect(status().is5xxServerError());assertThat(snapshot()).isEqualTo(before);
    }
    @Test void overlapIsRejectedAndOneDayIsAllowed() throws Exception {
        var row=create("2026-10-16","2026-10-16");assertThat(row.path("months").get(0).path("mm_qty").decimalValue()).isEqualByComparingTo("0.03");
        String before=snapshot();
        mvc.perform(post("/api/assignments").contentType("application/json").content(json.writeValueAsString(payload("2026-10-16","2026-10-31"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OVERLAP_ASSIGNMENT"));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void tkDetailKeepsNullPriceAndReturnsUnfixedExtensionCount() throws Exception {
        sql("UPDATE prj.prj SET cntrct_ty_cd='TK' WHERE prj_id="+project);
        var row=create("2026-10-16","2026-10-31");long id=row.path("asgn_id").asLong();
        sql("INSERT INTO asgn.asgn_chg_hist(asgn_id,asgn_chg_ty_cd,aply_dt,aft_val,fixed_yn) VALUES("+id+",'EXTEND','2026-10-17','2026-12-31','N'),("+id+",'EXTEND','2026-10-18','2026-11-30','Y'),("+id+",'RATE','2026-10-19','7,000,000원','N')");
        mvc.perform(get("/api/assignments/{id}",id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.extend_count").value(1))
            .andExpect(jsonPath("$.data.bill_untprc_amt").isEmpty()).andExpect(jsonPath("$.data.rates[0].bill_untprc_amt").isEmpty())
            .andExpect(jsonPath("$.data.pay_untprc_amt").doesNotExist());
    }
}
