package com.sicost.contractchanges;

import static com.sicost.contractchanges.ProjectContractChangeModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.api.ApiExceptionHandler;
import com.sicost.common.api.RequestIdFilter;
import com.sicost.common.security.*;
import java.math.BigDecimal;
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

class ProjectContractChangePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ProjectContractChangeService service;
    ProjectContractChangeMapper mapper;
    MockMvc mvc;
    ObjectMapper json;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/contractchanges/ProjectContractChangeMapper.xml"));
        factory.afterPropertiesSet(); mapper=new SqlSessionTemplate(factory.getObject()).getMapper(ProjectContractChangeMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,ProjectContractChangeService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(ProjectContractChangeMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
        context.refresh(); service=context.getBean(ProjectContractChangeService.class);
        json = context.getBean(ObjectMapper.class).copy()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new ProjectContractChangeController(service, json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,cntrct_bgn_dt,cntrct_end_dt,cur_end_dt,init_cntrct_amt,cur_cntrct_amt,cntrct_mm_qty,trgt_cst_amt) VALUES(1,'P-2601','계약 시험','TK','2026-01-01','2026-12-31','2026-12-31',500000000,500000000,0,123456789)");
        sql("INSERT INTO prj.phs(prj_id,phs_seq,phs_nm,wght_rt,plan_bgn_dt,plan_end_dt) VALUES(1,1,'분석',100,'2026-01-01','2026-12-31')");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close(); if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);} }
    String value(String text) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('project',(SELECT row_to_json(p) FROM prj.prj p WHERE prj_id=1),'changes',(SELECT json_agg(h ORDER BY cntrct_chg_id) FROM prj.cntrct_chg_hist h),'audit',(SELECT json_agg(a ORDER BY data_chg_hist_id) FROM sys.data_chg_hist a),'phases',(SELECT json_agg(p ORDER BY phs_id) FROM prj.phs p))::text");
    }
    ChangeInput input(String type,String status,long amount) {
        return new ChangeInput(type,status,LocalDate.of(2026,10,22),amount,new BigDecimal("-1.25"),null,null,"범위 변경");
    }
    @Test void anonymousPostPlanAndConfirmPersistHistoryCurrentAndAuditAtomically() throws Exception {
        var response = mvc.perform(post("/api/projects/1/contract-changes").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input("ADD", "PLAN", 100000000))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.cntrct_chg_stts_cd").value("PLAN")).andReturn();
        long id = json.readTree(response.getResponse().getContentAsByteArray()).path("data").path("chgId").asLong();
        assertThat(value("SELECT cur_cntrct_amt FROM prj.prj WHERE prj_id=1")).isEqualTo("500000000");
        mvc.perform(post("/api/projects/1/contract-changes/{chgId}/confirm", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.cntrct_chg_stts_cd").value("CONFIRM"))
            .andExpect(jsonPath("$.data.aft_cntrct_amt").value(600000000))
            .andExpect(jsonPath("$.data.cnfrm_at").isNotEmpty()).andExpect(jsonPath("$.data.cnfrm_user_id").isEmpty());
        assertThat(value("SELECT count(*) FROM prj.cntrct_chg_hist WHERE prj_id=1 AND cntrct_chg_stts_cd='CONFIRM' AND cnfrm_at IS NOT NULL AND cnfrm_user_id IS NULL AND realoc_need_yn='Y'")).isEqualTo("1");
        assertThat(value("SELECT cur_cntrct_amt FROM prj.prj WHERE prj_id=1")).isEqualTo("600000000");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.cntrct_chg_hist' AND obj_rec_id='1'")).isEqualTo("2");
        mvc.perform(get("/api/projects/1/contract-changes")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].bfr_cntrct_amt").value(500000000))
            .andExpect(jsonPath("$.data.items[0].aft_cntrct_amt").value(600000000));
        String before = snapshot();
        mvc.perform(delete("/api/projects/1/contract-changes/{chgId}", id)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CHANGE_CONFIRMED"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void anonymousPutPlanThenDeletePersistsOnlyOwnedChangeAndAudit() throws Exception {
        var row = service.create(1, input("ADD", "PLAN", 100));
        var before = mapper.project(1);
        mvc.perform(put("/api/projects/1/contract-changes/{chgId}", row.chgId())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("ADD", "PLAN", 200))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.cntrct_chg_stts_cd").value("PLAN"))
            .andExpect(jsonPath("$.data.aft_cntrct_amt").value(500000200));
        assertThat(value("SELECT aft_cntrct_amt FROM prj.cntrct_chg_hist WHERE prj_id=1")).isEqualTo("500000200");
        mvc.perform(delete("/api/projects/1/contract-changes/{chgId}", row.chgId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.chgId").value(row.chgId()));
        assertThat(value("SELECT count(*) FROM prj.cntrct_chg_hist WHERE prj_id=1")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.cntrct_chg_hist' AND obj_rec_id='1'")).isEqualTo("3");
        assertThat(mapper.project(1)).isEqualTo(before);
    }
    @Test void plannedDeltaSurvivesReadAndAppliesAgainstLatestCurrent() throws Exception {
        String phases=value("SELECT json_agg(p)::text FROM prj.phs p");
        var row=service.create(1,input("ADD","PLAN",100000000));
        assertThat(mapper.project(1).cur_cntrct_amt()).isEqualTo(500000000);
        assertThat(service.list(1).items()).containsExactly(row);
        service.create(1,input("ADD","CONFIRM",50000000));
        var confirmed=service.confirm(1,row.chgId());
        assertThat(confirmed.bfr_cntrct_amt()).isEqualTo(550000000);
        assertThat(confirmed.aft_cntrct_amt()).isEqualTo(650000000);
        assertThat(confirmed.cnfrm_at()).isNotNull(); assertThat(confirmed.cnfrm_user_id()).isNull();
        assertThat(confirmed.realoc_need_yn()).isEqualTo("Y");
        assertThat(mapper.project(1).cntrct_mm_qty()).isEqualByComparingTo("-2.50");
        assertThat(value("SELECT trgt_cst_amt FROM prj.prj WHERE prj_id=1")).isEqualTo("123456789");
        assertThat(value("SELECT json_agg(p)::text FROM prj.phs p")).isEqualTo(phases);
        String before=snapshot(); service.confirm(1,row.chgId()); assertThat(snapshot()).isEqualTo(before);
    }
    @Test void planEditAndDeletePreserveProjectAndOtherProjectIntegrity() throws Exception {
        var row=service.create(1,input("ADD","PLAN",100));
        var project=mapper.project(1); var edited=service.update(1,row.chgId(),input("CUT","PLAN",200));
        assertThat(edited.chgId()).isEqualTo(row.chgId()); assertThat(edited.aft_cntrct_amt()).isEqualTo(499999800);
        assertThat(mapper.project(1)).isEqualTo(project);
        assertThat(mapper.row(new ChangeKey(2,row.chgId()))).isNull();
        service.delete(1,row.chgId()); assertThat(service.list(1).items()).isEmpty();
        assertThat(mapper.project(1)).isEqualTo(project);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.cntrct_chg_hist' AND obj_rec_id='1'")).isEqualTo("3");
    }
    @Test void cutFailurePreservesRowCurrentPhasesAndHistory() throws Exception {
        var row=service.create(1,input("CUT","PLAN",500000000)); String before=snapshot();
        assertThatThrownBy(()->service.confirm(1,row.chgId())).isInstanceOfSatisfying(ApiException.class,
            e->assertThat(e.getMessage()).isEqualTo("감액 뒤 계약금액이 0원 이하가 됩니다."));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void confirmedPutAndDeletePreserveEverything() throws Exception {
        var row=service.create(1,input("ADD","CONFIRM",100)); String before=snapshot();
        assertThatThrownBy(()->service.update(1,row.chgId(),input("ADD","PLAN",200)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CHANGE_CONFIRMED"));
        assertThatThrownBy(()->service.delete(1,row.chgId())).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void termUsesActualCommitOrderAndFreeChangesNothingElse() throws Exception {
        var first=service.create(1,new ChangeInput("TERM","CONFIRM",LocalDate.of(2026,10,22),null,null,LocalDate.of(2027,3,31),null,"연장"));
        var second=service.create(1,new ChangeInput("TERM","CONFIRM",LocalDate.of(2026,1,1),null,null,LocalDate.of(2027,2,28),null,"소급 입력"));
        assertThat(mapper.project(1).cur_end_dt()).isEqualTo(LocalDate.of(2027,2,28));
        assertThat(second.bfr_end_dt()).isEqualTo(first.aft_end_dt());
        var project=mapper.project(1);
        var free=service.create(1,new ChangeInput("FREE","CONFIRM",LocalDate.of(2026,10,22),null,null,null,new BigDecimal("2.50"),"지원"));
        assertThat(free.realoc_need_yn()).isEqualTo("N"); assertThat(free.aft_cntrct_amt()).isNull();
        assertThat(mapper.project(1)).isEqualTo(project);
        assertThat(value("SELECT free_sprt_yn FROM prj.prj WHERE prj_id=1")).isEqualTo("N");
    }
    @ParameterizedTest @ValueSource(strings={"CREATE","UPDATE","CONFIRM","DELETE"})
    void auditFailureRollsBackWholeMutation(String operation) throws Exception {
        var row=operation.equals("CREATE")?null:service.create(1,input("ADD","PLAN",100));
        String before=snapshot(); sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()-> {
            switch(operation) {
                case "CREATE" -> service.create(1,input("ADD","CONFIRM",100));
                case "UPDATE" -> service.update(1,row.chgId(),input("ADD","CONFIRM",200));
                case "CONFIRM" -> service.confirm(1,row.chgId());
                default -> service.delete(1,row.chgId());
            }
        }).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void differentConcurrentConfirmationsBothApplyAndSameOneIsIdempotent() throws Exception {
        var one=service.create(1,input("ADD","PLAN",100)); var two=service.create(1,input("ADD","PLAN",200));
        var executor=Executors.newFixedThreadPool(3); var start=new CountDownLatch(1);
        try {
            var a=executor.submit(()->{start.await(); return service.confirm(1,one.chgId());});
            var b=executor.submit(()->{start.await(); return service.confirm(1,two.chgId());});
            var c=executor.submit(()->{start.await(); return service.confirm(1,one.chgId());});
            start.countDown(); a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS); c.get(10,TimeUnit.SECONDS);
            assertThat(mapper.project(1).cur_cntrct_amt()).isEqualTo(500000300);
            assertThat(value("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo("4");
        } finally {executor.shutdownNow();}
    }
}
