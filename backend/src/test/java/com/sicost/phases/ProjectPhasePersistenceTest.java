package com.sicost.phases;

import static com.sicost.phases.ProjectPhaseModels.*;
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
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectPhasePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ProjectPhaseService service;
    ProjectPhaseMapper mapper;
    MockMvc mvc;
    static final LocalDate TODAY = LocalDate.of(2026,10,23);
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/phases/ProjectPhaseMapper.xml"));
        factory.afterPropertiesSet(); mapper=new SqlSessionTemplate(factory.getObject()).getMapper(ProjectPhaseMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,ProjectPhaseService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(ProjectPhaseMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneOffset.UTC));
        context.refresh(); service=context.getBean(ProjectPhaseService.class);
        var json=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc=MockMvcBuilders.standaloneSetup(new ProjectPhaseController(service,json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(1,'P-2601','단계 시험','TK','RUN'),(2,'P-2602','다른 프로젝트','TK','RUN')");
        sql("INSERT INTO prj.phs(phs_id,prj_id,phs_seq,phs_nm,wght_rt,plan_bgn_dt,plan_end_dt) VALUES(7,1,1,'개발',35.25,'2026-10-01','2026-10-31'),(8,1,2,'테스트',64.75,'2026-11-01','2026-12-31'),(9,2,1,'개발',100,'2026-10-01','2026-10-31')");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null)context.close(); if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);} }
    String value(String text) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('phases',(SELECT json_agg(p ORDER BY phs_id) FROM prj.phs p),'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    String date() throws Exception {return value("SELECT cmpl_dt FROM prj.phs WHERE phs_id=7");}
    long currentPhase() throws Exception {
        return Long.parseLong(value("SELECT phs_id FROM prj.phs WHERE prj_id=1 ORDER BY (cmpl_dt IS NOT NULL),CASE WHEN cmpl_dt IS NULL THEN phs_seq ELSE -phs_seq END LIMIT 1"));
    }
    @Test void anonymousCompletionPersistsSeoulTodayNextCurrentPhaseAndExactlyOneChildHistory() throws Exception {
        assertThat(currentPhase()).isEqualTo(7);
        mvc.perform(post("/api/projects/1/phases/7/complete")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.cmpl_dt").value("2026-10-23")).andExpect(jsonPath("$.data.status").value("완료"));
        assertThat(date()).isEqualTo("2026-10-23"); assertThat(currentPhase()).isEqualTo(8);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.phs' AND obj_rec_id='7' AND clmn_nm='cmpl_dt' AND data_chg_ty_cd='UPDATE' AND bfr_val IS NULL AND aft_val='2026-10-23'")).isEqualTo("1");
    }
    @Test void delayedCompletionStatusHasPriority() throws Exception {
        sql("UPDATE prj.phs SET plan_end_dt='2026-10-02' WHERE phs_id=7");
        mvc.perform(post("/api/projects/1/phases/7/complete")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("완료"));
    }
    @Test void concurrentCompletionsHaveOne200OneSpecified409AndOneHistory() throws Exception {
        var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        Callable<Integer> request=()->{start.await(); var response=mvc.perform(post("/api/projects/1/phases/7/complete")).andReturn().getResponse();
            if(response.getStatus()==409)assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("ALREADY_DONE","이미 완료된 단계입니다.");
            return response.getStatus();};
        try {
            var a=executor.submit(request);var b=executor.submit(request);start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(date()).isEqualTo("2026-10-23");
            assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.phs' AND obj_rec_id='7' AND clmn_nm='cmpl_dt'")).isEqualTo("1");
        } finally {executor.shutdownNow();}
    }
    @Test void futureStartIs409AndNothingChanges() throws Exception {
        String before=snapshot();
        mvc.perform(post("/api/projects/1/phases/8/complete")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHASE_NOT_STARTED"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void nullDatesArePreservedAndCompletionCannotInventStart() throws Exception {
        sql("UPDATE prj.phs SET plan_bgn_dt=NULL,plan_end_dt=NULL WHERE phs_id=7");String before=snapshot();
        mvc.perform(post("/api/projects/1/phases/7/complete")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHASE_NOT_STARTED"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void uncompletePersistsNullRecomputedStatusAndPreviousDateHistoryAndRepeatIsNoOp() throws Exception {
        sql("UPDATE prj.phs SET cmpl_dt='2026-10-21',plan_end_dt='2026-10-22' WHERE phs_id=7");
        mvc.perform(post("/api/projects/1/phases/7/uncomplete")).andExpect(status().isOk()).andExpect(jsonPath("$.data.cmpl_dt").isEmpty())
            .andExpect(jsonPath("$.data.status").value("지연"));
        assertThat(date()).isNull(); assertThat(currentPhase()).isEqualTo(7);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.phs' AND obj_rec_id='7' AND clmn_nm='cmpl_dt' AND bfr_val='2026-10-21' AND aft_val IS NULL")).isEqualTo("1");
        String before=snapshot();mvc.perform(post("/api/projects/1/phases/7/uncomplete")).andExpect(status().isOk());
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"complete","uncomplete","period"})
    void foreignAndMissingPhaseAre404AndAllRowsUnchanged(String operation) throws Exception {
        sql("UPDATE prj.prj SET prj_stts_cd='END' WHERE prj_id=1");String before=snapshot();
        for(long id:new long[]{9,999}) {
            var request=operation.equals("period")?put("/api/projects/1/phases/"+id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan_bgn_dt\":\"2026-09-01\",\"plan_end_dt\":\"2026-12-31\"}"):post("/api/projects/1/phases/"+id+"/"+operation);
            mvc.perform(request).andExpect(status().isNotFound()); assertThat(snapshot()).isEqualTo(before);
        }
    }
    @ParameterizedTest @ValueSource(strings={"END","LOST","N"})
    void closedProjectRejectsWritesAndKeepsCompletedDate(String state) throws Exception {
        sql("UPDATE prj.phs SET cmpl_dt='2026-10-22' WHERE phs_id=7");
        sql(state.equals("N")?"UPDATE prj.prj SET use_yn='N' WHERE prj_id=1":"UPDATE prj.prj SET prj_stts_cd='"+state+"' WHERE prj_id=1");
        String before=snapshot();
        mvc.perform(post("/api/projects/1/phases/7/uncomplete")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));
        mvc.perform(post("/api/projects/1/phases/7/complete")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));
        mvc.perform(put("/api/projects/1/phases/7").contentType(MediaType.APPLICATION_JSON)
            .content("{\"plan_bgn_dt\":\"2026-09-01\",\"plan_end_dt\":\"2026-12-31\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void periodPreservesNamesWeightsCountAndCompletionWithChangedColumnAudit() throws Exception {
        String identity=value("SELECT json_agg(json_build_array(phs_id,phs_nm,wght_rt,phs_seq,cmpl_dt) ORDER BY phs_id)::text FROM prj.phs");
        mvc.perform(put("/api/projects/1/phases/7").contentType(MediaType.APPLICATION_JSON)
            .content("{\"plan_bgn_dt\":\"2026-09-01\",\"plan_end_dt\":\"2026-12-31\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.plan_bgn_dt").value("2026-09-01"))
            .andExpect(jsonPath("$.data.plan_end_dt").value("2026-12-31"));
        assertThat(value("SELECT plan_bgn_dt FROM prj.phs WHERE phs_id=7")).isEqualTo("2026-09-01");
        assertThat(value("SELECT json_agg(json_build_array(phs_id,phs_nm,wght_rt,phs_seq,cmpl_dt) ORDER BY phs_id)::text FROM prj.phs")).isEqualTo(identity);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.phs' AND obj_rec_id='7' AND ((clmn_nm='plan_bgn_dt' AND bfr_val='2026-10-01' AND aft_val='2026-09-01') OR (clmn_nm='plan_end_dt' AND bfr_val='2026-10-31' AND aft_val='2026-12-31'))")).isEqualTo("2");
        String before=snapshot();service.update(1,7,new PeriodInput(LocalDate.of(2026,9,1),LocalDate.of(2026,12,31)));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void reversedPeriod400LeavesDatesUnchanged() throws Exception {
        String before=snapshot();
        mvc.perform(put("/api/projects/1/phases/7").contentType(MediaType.APPLICATION_JSON)
            .content("{\"plan_bgn_dt\":\"2026-11-01\",\"plan_end_dt\":\"2026-10-01\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("plan_end_dt"))
            .andExpect(jsonPath("$.message").value("예정 종료일은 시작일과 같거나 뒤여야 합니다."));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void completedPeriod409PreservesCompletionAndDates() throws Exception {
        sql("UPDATE prj.phs SET cmpl_dt='2026-10-22' WHERE phs_id=7");String before=snapshot();
        mvc.perform(put("/api/projects/1/phases/7").contentType(MediaType.APPLICATION_JSON)
            .content("{\"plan_bgn_dt\":\"2026-09-01\",\"plan_end_dt\":\"2026-12-31\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHASE_DONE"));assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"complete","uncomplete","period"})
    void auditFailureRollsBackBusinessRowAndHistory(String operation) throws Exception {
        if(operation.equals("uncomplete"))sql("UPDATE prj.phs SET cmpl_dt='2026-10-22' WHERE phs_id=7");
        String before=snapshot();sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_phase_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->{
            switch(operation) {
                case "complete" -> service.complete(1,7);
                case "uncomplete" -> service.uncomplete(1,7);
                default -> service.update(1,7,new PeriodInput(LocalDate.of(2026,9,1),LocalDate.of(2026,12,31)));
            }
        }).isInstanceOf(RuntimeException.class);assertThat(snapshot()).isEqualTo(before);
    }
}
