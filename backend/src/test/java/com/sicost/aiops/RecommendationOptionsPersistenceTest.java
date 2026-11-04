package com.sicost.aiops;

import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.ai.*;
import com.sicost.common.api.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import static com.sicost.aiops.RecommendationOptionsModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecommendationOptionsPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;
    RecommendationOptionsMapper mapper;
    RecommendationOptionsService service;
    MockMvc mvc;
    long project,developer,customer;
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null && !adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null && password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.create(s,database); }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures=new AssignmentTestSupport(source);
        project=fixtures.createProject(); developer=fixtures.createDeveloper();
        customer=jdbc.queryForObject("INSERT INTO mst.ptnr(ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(?,'CUST','추천 시험 고객','123-45-67890') RETURNING ptnr_id",Long.class,key());
        jdbc.update("UPDATE prj.prj SET cust_ptnr_id=? WHERE prj_id=?",customer,project);
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/aiops/RecommendationOptionsMapper.xml")); factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(RecommendationOptionsMapper.class);
        var access=new AiAccessGuard(new MockEnvironment());
        service=new RecommendationOptionsService(mapper,access,new AiOwnerSession(AssignmentTestSupport.CLOCK),
            new AiSourceReads(new DataSourceTransactionManager(source)),AssignmentTestSupport.CLOCK);
        mvc=MockMvcBuilders.standaloneSetup(new RecommendationOptionsController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(fixtures.json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void removeDatabase() throws Exception {
        if(fixtures!=null)fixtures.close(); if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.drop(s,database); }
    }
    static String key() { return "T-"+UUID.randomUUID().toString().substring(0,12); }
    long plan(String job,String grade,String mm) {
        return jdbc.queryForObject("INSERT INTO prj.psn_plan(prj_id,plan_ym,job_cd,grd_cd,need_mm_qty) VALUES(?,'2026-11',?,?,?) RETURNING psn_plan_id",Long.class,project,job,grade,new BigDecimal(mm));
    }
    long need(String status,int capacity) {
        long opportunity=jdbc.queryForObject("INSERT INTO bsn.oppt(oppt_no,oppt_nm,cust_ptnr_id,oppt_stts_cd) VALUES(?,'추천 시험 기회',?,?) RETURNING oppt_id",Long.class,key(),customer,status);
        return jdbc.queryForObject("INSERT INTO bsn.oppt_need(oppt_id,job_cd,grd_cd,psn_cnt,bgn_ym,mnth_qty,mth_mm_qty) VALUES(?,'BE','SR',?,'2026-11',3,0.50) RETURNING need_id",Long.class,opportunity,capacity);
    }
    long assignment(long dev,String start,String end,String extension) {
        return jdbc.queryForObject("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,ext_psblty_cd) VALUES(?,?,?,'BE',?::date,?::date,?) RETURNING asgn_id",Long.class,key(),project,dev,start,end,extension);
    }
    Map<String,String> snapshot() {
        var result=new LinkedHashMap<String,String>();
        for(String table:List.of("prj.prj","prj.psn_plan","asgn.asgn","asgn.asgn_mth","asgn.tmpr_asgn","asgn.asgn_chg_hist",
            "bsn.oppt","bsn.oppt_need","bill.bill","bill.bill_dtl","cst.mth_cst","cst.cst_aloc","fin.pay","fin.mth_cls","fin.mth_pl",
            "ai.frcst_exec","ai.frcst_rslt","ai.rcmd_rqst","ai.rcmd_rslt","cmn.oprt_std","sys.data_chg_hist")) {
            result.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+table+" t",String.class));
        }
        return result;
    }
    @Test void realMapperMaterializesProjectAndOpportunityPrefillsWithoutChangingAnySource() throws Exception {
        long slot=plan("BE","SR","1.50"),opportunityNeed=need("OPEN",2);
        assignment(developer,"2026-01-01","2026-12-31","H");
        var before=snapshot();
        var data=service.get(new MockHttpServletRequest(),Map.of());
        var projectOption=data.sources().stream().filter(s->s.projectId()!=null && s.projectId()==project).findFirst().orElseThrow();
        assertThat(projectOption.criteria().requestedMm()).isEqualByComparingTo("0.75");
        assertThat(projectOption.criteria().durationMonths()).isEqualTo(1);
        var opportunity=data.sources().stream().filter(s->Objects.equals(s.needId(),opportunityNeed)).findFirst().orElseThrow();
        assertThat(opportunity.criteria().requestedMm()).isEqualByComparingTo("0.50");
        assertThat(opportunity.criteria().durationMonths()).isEqualTo(3);
        assertThat(opportunity.remainingHeadcount()).isEqualTo(2);
        assertThat(mapper.sources("2026-10",null)).extracting(SourceRow::rowId).contains(slot);
        mvc.perform(get(RecommendationOptionsApiTest.URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.defaultStartYm").value("2026-11"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void realSourceFiltersExcludeFullInactiveClosedAndKeepSeparateGradeSlots() {
        long first=plan("BE","JR","1.00"),second=plan("BE","SR","1.00");
        long assigned=assignment(developer,"2026-10-01","2026-12-31",null);
        jdbc.update("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(?,'2026-11',1.00)",assigned);
        long full=need("OPEN",1),lost=need("LOST",1),inactive=need("OPEN",1);
        jdbc.update("INSERT INTO asgn.tmpr_asgn(need_id,dev_id,bgn_ym,end_ym,tmpr_asgn_stts_cd) VALUES(?,?,'2026-11','2027-01','ACTIVE')",full,developer);
        jdbc.update("UPDATE bsn.oppt_need SET use_yn='N' WHERE need_id=?",inactive);
        var rows=mapper.sources("2026-10",null);
        assertThat(rows.stream().filter(s->"PROJECT".equals(s.sourceType())).map(SourceRow::rowId)).contains(second).doesNotContain(first);
        assertThat(rows.stream().filter(s->"OPP".equals(s.sourceType())).map(SourceRow::rowId)).doesNotContain(full,lost,inactive);
        assertThat(mapper.sources("2026-10","does-not-exist")).isEmpty();
    }
    @Test void actualWithdrawalsIncludeEveryExtensionAndNextConfirmedButNotPastOrBeyondWindow() throws Exception {
        var ids=new ArrayList<Long>();
        for(String extension:Arrays.asList("H","M","L",null)) {
            long dev=fixtures.createDeveloper(); ids.add(dev);
            assignment(dev,"2026-01-01","2026-12-31",extension);
        }
        assignment(ids.get(0),"2027-01-01","2027-03-31",null);
        long outside=fixtures.createDeveloper(); assignment(outside,"2026-01-01","2027-01-01",null);
        var rows=mapper.developers(new ExitQuery(LocalDate.of(2026,10,14),LocalDate.of(2026,12,31),null,null));
        assertThat(rows).extracting(ExitOption::devId).containsAll(ids).doesNotContain(outside);
        assertThat(rows.stream().filter(r->r.devId()==ids.get(0)).findFirst().orElseThrow().hasNextConfirmedAssignment()).isTrue();
        assertThat(rows).extracting(ExitOption::extensionLikelihood).contains("H","M","L",null);
    }
    @Test void handoffRechecksChangedAndUnavailableSourceWithNoWrites() {
        plan("BE","SR","1.00"); var request=new MockHttpServletRequest();
        long handle=service.get(request,Map.of()).sources().get(0).sourceId();
        assertThat(service.resolveCurrentSource(request,handle).jobCode()).isEqualTo("BE");
        jdbc.update("UPDATE prj.psn_plan SET job_cd='FE' WHERE prj_id=?",project);
        var before=snapshot();
        assertThatThrownBy(()->service.get(request,Map.of("sourceId",Long.toString(handle))))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(409));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void directlySelectedUnassignedDeveloperHasUnknownExitNotFabricatedDate() {
        var rows=mapper.developers(new ExitQuery(LocalDate.of(2026,10,14),LocalDate.of(2026,12,31),developer,null));
        assertThat(rows).hasSize(1); assertThat(rows.get(0).exitDate()).isNull(); assertThat(rows.get(0).assumedExitYm()).isNull();
    }
    @Test void defaultOptionsKeepUnknownExitAndReportFutureConfirmedAssignment() {
        var query=new ExitQuery(LocalDate.of(2026,10,14),LocalDate.of(2026,12,31),null,null);
        var unknown=mapper.developers(query).stream().filter(r->r.devId()==developer).findFirst().orElseThrow();
        assertThat(unknown.exitDate()).isNull();assertThat(unknown.assumedExitYm()).isNull();
        assertThat(unknown.hasNextConfirmedAssignment()).isFalse();
        assignment(developer,"2027-01-01","2027-03-31",null);
        var planned=mapper.developers(query).stream().filter(r->r.devId()==developer).findFirst().orElseThrow();
        assertThat(planned.exitDate()).isNull();assertThat(planned.assumedExitYm()).isNull();
        assertThat(planned.hasNextConfirmedAssignment()).isTrue();
    }
}
