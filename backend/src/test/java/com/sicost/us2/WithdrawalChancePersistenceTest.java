package com.sicost.us2;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.assignments.*;
import com.sicost.changes.*;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WithdrawalChancePersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;
    AnnotationConfigApplicationContext context;
    MockMvc mvc;
    long assignment;
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
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/changes/AssignmentChangeMapper.xml"),
            new ClassPathResource("mappers/assignments/AssignmentMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"));
        factory.afterPropertiesSet(); var session=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        context.register(AssignmentTestSupport.Transactions.class,AssignmentChangeService.class,
            AssignmentService.class,AssignmentConcurrencyService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(AssignmentChangeMapper.class,()->session.getMapper(AssignmentChangeMapper.class));
        context.registerBean(AssignmentMapper.class,()->session.getMapper(AssignmentMapper.class));
        context.registerBean(AssignmentConcurrencyMapper.class,()->session.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->AssignmentTestSupport.CLOCK);
        context.refresh();
        mvc=MockMvcBuilders.standaloneSetup(
            new AssignmentChangeController(context.getBean(AssignmentChangeService.class),fixtures.json),
            new AssignmentController(context.getBean(AssignmentService.class),fixtures.json,AssignmentTestSupport.CLOCK))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        long project=fixtures.createProject(), developer=fixtures.createDeveloper();
        var body=new LinkedHashMap<String,Object>();
        body.put("prj_id",project); body.put("dev_id",developer); body.put("job_cd","BE");
        body.put("bgn_dt","2026-10-14"); body.put("end_dt","2026-12-31");
        body.put("asgn_rt",1); body.put("asgn_se_cd","NEW");
        assignment=fixtures.createAssignment(mvc,body).path("asgn_id").asLong();
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close(); if(fixtures!=null) fixtures.close(); if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    LocalDateTime version() {
        return jdbc.queryForObject("SELECT coalesce(a.updated_at,a.created_at) FROM asgn.asgn a WHERE a.asgn_id=?",LocalDateTime.class,assignment);
    }
    String chance() { return jdbc.queryForObject("SELECT ext_psblty_cd FROM asgn.asgn WHERE asgn_id=?",String.class,assignment); }
    long count() {
        return jdbc.queryForObject("SELECT count(*) FROM asgn.asgn_chg_hist WHERE asgn_id=? AND asgn_chg_ty_cd='EXT_CHANCE'",Long.class,assignment);
    }
    String financial() {
        return jdbc.queryForObject("""
            SELECT json_build_object('rates',(SELECT json_agg(r ORDER BY r.aply_bgn_dt) FROM asgn.untprc_hist r WHERE r.asgn_id=?),
              'months',(SELECT json_agg(m ORDER BY m.work_ym) FROM asgn.asgn_mth m WHERE m.asgn_id=?))::text
            """,String.class,assignment,assignment);
    }
    String snapshot() {
        return jdbc.queryForObject("""
            SELECT json_build_object('assignment',(SELECT row_to_json(a) FROM asgn.asgn a WHERE a.asgn_id=?),
              'history',(SELECT json_agg(h ORDER BY h.asgn_chg_id) FROM asgn.asgn_chg_hist h WHERE h.asgn_id=?))::text
            """,String.class,assignment,assignment)+financial();
    }
    Map<String,Object> input(String value) {
        var body=new LinkedHashMap<String,Object>();
        body.put("asgn_chg_ty_cd","EXT_CHANCE"); body.put("aply_dt","2026-10-14");
        body.put("updated_at",version().toString()); body.put("ext_psblty_cd",value); return body;
    }
    ResultActions send(Map<String,Object> body) throws Exception {
        return mvc.perform(post("/api/assignments/{id}/changes",assignment).contentType("application/json")
            .content(fixtures.json.writeValueAsBytes(body)));
    }
    JsonNode read(ResultActions result) throws Exception {
        return fixtures.json.readTree(result.andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    @Test void nullToHighCommitsExactlyOneHistoryVisibleInDetailAfterNewRead() throws Exception {
        LocalDateTime old=version(); String financial=financial();
        var result=read(send(input("H")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignment.ext_psblty_cd").value("H"))
            .andExpect(jsonPath("$.data.change.bfr_val").value("미정"))
            .andExpect(jsonPath("$.data.change.aft_val").value("높음")));
        assertThat(chance()).isEqualTo("H"); assertThat(count()).isEqualTo(1);
        assertThat(version()).isAfter(old);
        assertThat(LocalDateTime.parse(result.path("assignment").path("updated_at").asText())).isEqualTo(version());
        assertThat(financial()).isEqualTo(financial);
        mvc.perform(get("/api/assignments/{id}/changes",assignment).param("type","EXT_CHANCE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].bfr_val").value("미정"))
            .andExpect(jsonPath("$.data.items[0].aft_val").value("높음"))
            .andExpect(jsonPath("$.data.items[0].retro_yn").value("N"));
        mvc.perform(get("/api/assignments/{id}",assignment)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ext_psblty_cd").value("H"));
    }
    @Test void identicalHighRejectsWithoutVersionOrHistoryChange() throws Exception {
        send(input("H")).andExpect(status().isOk()); String before=snapshot();
        send(input("H")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ext_psblty_cd"));
        assertThat(snapshot()).isEqualTo(before); assertThat(count()).isEqualTo(1);
    }
    @Test void identicalNullIsNotAnImplicitCreate() throws Exception {
        String before=snapshot(); send(input(null)).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before); assertThat(count()).isZero();
    }
    @Test void explicitClearPersistsNullAndNamedHistory() throws Exception {
        send(input("H")).andExpect(status().isOk());
        send(input(null)).andExpect(status().isOk()).andExpect(jsonPath("$.data.change.bfr_val").value("높음"))
            .andExpect(jsonPath("$.data.change.aft_val").value("미정"));
        assertThat(chance()).isNull(); assertThat(count()).isEqualTo(2);
    }
    @Test void secondWindowOldTokenCannotOverwriteFirstWindow() throws Exception {
        var first=input("H"); var second=input("L");
        send(first).andExpect(status().isOk()); String committed=snapshot();
        send(second).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_ASSIGNMENT"));
        assertThat(snapshot()).isEqualTo(committed); assertThat(chance()).isEqualTo("H"); assertThat(count()).isEqualTo(1);
    }
    @Test void simultaneousWindowsCommitOnlyOneChanceAndHistory() throws Exception {
        var first=input("H"); var second=input("L");
        var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<Integer> one=()-> { start.await(); return send(first).andReturn().getResponse().getStatus(); };
            Callable<Integer> two=()-> { start.await(); return send(second).andReturn().getResponse().getStatus(); };
            var a=executor.submit(one); var b=executor.submit(two); start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(count()).isEqualTo(1);
            String after=jdbc.queryForObject("SELECT aft_val FROM asgn.asgn_chg_hist WHERE asgn_id=? AND asgn_chg_ty_cd='EXT_CHANCE'",String.class,assignment);
            assertThat(after).isEqualTo("H".equals(chance())?"높음":"낮음");
        } finally { executor.shutdownNow(); }
    }
    @Test void historyInsertFailureRollsBackChanceVersionAndEveryOtherAssignmentTable() throws Exception {
        jdbc.execute("ALTER TABLE asgn.asgn_chg_hist ADD CONSTRAINT reject_chance_history CHECK(false) NOT VALID");
        String before=snapshot();
        send(input("H")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SERVER_ERROR"));
        assertThat(snapshot()).isEqualTo(before); assertThat(chance()).isNull(); assertThat(count()).isZero();
    }
}
