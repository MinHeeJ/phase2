package com.sicost.staffrecommendations;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sicost.aiops.*;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.ai.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.staffrecommendations.StaffRecommendationModels.*;

class StaffRecommendationPersistenceTest {
    String adminUrl,user,password,database;DriverManagerDataSource source;JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;AnnotationConfigApplicationContext context;MockMvc mvc;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    long developer,project,customer;MockHttpSession owner=new MockHttpSession();
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures=new AssignmentTestSupport(source);project=fixtures.createProject();developer=fixtures.createDeveloper();
        customer=jdbc.queryForObject("INSERT INTO mst.ptnr(ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(?,'CUST','추천 시험 고객','123-45-67890') RETURNING ptnr_id",Long.class,"T-"+UUID.randomUUID().toString().substring(0,12));
        jdbc.update("UPDATE prj.prj SET cust_ptnr_id=? WHERE prj_id=?",customer,project);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/staffrecommendations/StaffRecommendationMapper.xml"),new ClassPathResource("mappers/aiops/RecommendationOptionsMapper.xml"));factory.afterPropertiesSet();var sql=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();context.register(AssignmentTestSupport.Transactions.class,StaffRecommendationService.class,RecommendationBoundary.class,
            AiAccessGuard.class,AiOwnerSession.class,AiSensitiveGuard.class,AiSourceReads.class,RecommendationOptionsService.class,RecommendationCriteriaService.class,AiQuestionSanitizer.class,AiLocalIntentParser.class,CareerCardService.class,ExcelExportService.class);
        context.registerBean(javax.sql.DataSource.class,()->source);context.registerBean(StaffRecommendationMapper.class,()->sql.getMapper(StaffRecommendationMapper.class));
        context.registerBean(RecommendationOptionsMapper.class,()->sql.getMapper(RecommendationOptionsMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.registerBean(ObjectMapper.class,()->json);context.refresh();
        mvc=MockMvcBuilders.standaloneSetup(new StaffRecommendationController(context.getBean(StaffRecommendationService.class),context.getBean(RecommendationCriteriaService.class),context.getBean(CareerCardService.class)))
            .setControllerAdvice(new RecommendationErrors(),new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null)context.close();if(fixtures!=null)fixtures.close();if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s,database);}
    }
    ObjectNode input() {var body=json.createObjectNode().put("direction",A);body.set("criteria",json.createObjectNode().put("jobCode","BE").put("startYm","2027-01").put("durationMonths",6).put("requestedMm",1));return body;}
    JsonNode run(ObjectNode input) throws Exception {
        return json.readTree(mvc.perform(post("/api/ai/staff-recommendations/search").session(owner).contentType("application/json").content(input.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    Map<String,String> sources() {
        Map<String,String> values=new LinkedHashMap<>();for(String table:List.of("mst.dev","prj.prj","asgn.asgn","asgn.asgn_mth","asgn.tmpr_asgn","bsn.oppt_need","cst.mth_cst","fin.mth_cls","ai.frcst_exec","ai.frcst_rslt"))
            values.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+table+" t",String.class));return values;
    }
    long need(String month,int months) {
        long opportunity=jdbc.queryForObject("INSERT INTO bsn.oppt(oppt_no,oppt_nm,cust_ptnr_id,oppt_stts_cd,win_prblt_rt) VALUES(?,'실제 시험 기회',?,'OPEN',60) RETURNING oppt_id",Long.class,"T-"+UUID.randomUUID().toString().substring(0,12),customer);
        return jdbc.queryForObject("INSERT INTO bsn.oppt_need(oppt_id,job_cd,psn_cnt,bgn_ym,mnth_qty) VALUES(?,'BE',2,?,?) RETURNING need_id",Long.class,opportunity,month,months);
    }
    @Test void sqlMaterializationAtomicSnapshotsAndBusinessRowsUnchanged() throws Exception {
        var before=sources();var first=run(input());var second=run(input());assertThat(first.path("results").size()).isEqualTo(1);assertThat(second.path("results").get(0).path("score")).isEqualTo(first.path("results").get(0).path("score"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.rcmd_rqst",Integer.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.rcmd_rslt",Integer.class)).isEqualTo(2);
        mvc.perform(get("/api/ai/staff-recommendations/{id}",first.path("requestId").asLong()).session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.evaluatedAt").value(first.path("evaluatedAt").asText()));assertThat(sources()).isEqualTo(before);
    }
    @Test void insertionFailureRollsBackRequestAndResults() throws Exception {
        jdbc.execute("CREATE FUNCTION ai.reject_recommendation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected failure'; END $$");
        jdbc.execute("CREATE TRIGGER recommendation_rejection BEFORE INSERT ON ai.rcmd_rslt FOR EACH ROW EXECUTE FUNCTION ai.reject_recommendation()");var before=sources();
        mvc.perform(post("/api/ai/staff-recommendations/search").session(owner).contentType("application/json").content(input().toString())).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.rcmd_rqst",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.rcmd_rslt",Integer.class)).isZero();assertThat(sources()).isEqualTo(before);
    }
    @Test void bThreeSlotsUseDedicatedTableAndRecommendedJanuary() throws Exception {
        for(int i=0;i<3;i++)need("2026-11",5);
        jdbc.update("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,ext_psblty_cd) VALUES(?,?,?,'BE',DATE '2026-01-01',DATE '2026-12-31','H')","T-"+UUID.randomUUID().toString().substring(0,12),project,developer);
        var input=json.createObjectNode().put("direction",B).put("devId",developer);var data=run(input);
        assertThat(data.path("results")).hasSize(3);for(var r:data.path("results")){assertThat(r.path("recommendedStartYm").asText()).isEqualTo("2027-01");assertThat(r.path("waitingDays").intValue()).isZero();}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.rcmd_next_rslt",Integer.class)).isEqualTo(3);assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.rcmd_rslt",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT rqst_ty_cd FROM ai.rcmd_rqst",String.class)).isEqualTo("NEXT");
    }
    @Test void aDecisionUpdatesOnlyDecisionAndDetectsChangedSource() throws Exception {
        var data=run(input());long id=data.path("results").get(0).path("resultId").longValue();String before=jdbc.queryForObject("SELECT cond::text FROM ai.rcmd_rqst",String.class);var original=sources();
        mvc.perform(patch("/api/ai/staff-recommendations/results/{id}/decision",id).session(owner).contentType("application/json").content("{\"direction\":\"PROJECT_TO_PERSON\",\"decision\":\"ADOPT\"}" )).andExpect(status().isOk()).andExpect(jsonPath("$.data.assignmentCreated").value(false));
        assertThat(jdbc.queryForObject("SELECT cond::text FROM ai.rcmd_rqst",String.class)).isEqualTo(before);assertThat(sources()).isEqualTo(original);
        var next=run(input());jdbc.update("UPDATE mst.dev SET main_job_cd='FE' WHERE dev_id=?",developer);
        mvc.perform(patch("/api/ai/staff-recommendations/results/{id}/decision",next.path("results").get(0).path("resultId").longValue()).session(owner).contentType("application/json").content("{\"direction\":\"PROJECT_TO_PERSON\",\"decision\":\"ADOPT\"}" )).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_RECOMMENDATION"));
    }
}
