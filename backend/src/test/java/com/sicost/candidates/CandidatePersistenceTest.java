package com.sicost.candidates;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.CurrentUser;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class CandidatePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    CandidateService service;
    CandidateMapper mapper;
    org.springframework.test.web.servlet.MockMvc mvc;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void isolate() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null && !adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null && password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/candidates/CandidateMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(CandidateMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,CandidateService.class,CurrentUser.class);
        context.registerBean(CandidateMapper.class,() -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,() -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,() -> Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();service=context.getBean(CandidateService.class);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CandidateController(service,json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void remove() throws Exception {if(context!=null)context.close();if(source==null)return;try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s,database);}}
    void sql(String statement,Object... args) {new org.springframework.jdbc.core.JdbcTemplate(source).update(statement,args);}
    long scalar(String statement,Object... args) {return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(statement,Long.class,args);}
    String text(String statement) {return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(statement,String.class);}
    Map<String,Object> body() {var b=new LinkedHashMap<String,Object>();b.put("cand_nm","같은 이름");b.put("cand_se_cd","FL");b.put("job_cd","BE");b.put("hope_untprc_amt",1000000);b.put("avail_dt","2026-11-01");b.put("skill",List.of("JAVA"));return b;}
    CandidateModels.CandidateDetail create(Map<String,Object> b) {return service.create(CandidateInput.parse(json.valueToTree(b)));}
    String snapshot() {return text("SELECT json_build_array((SELECT json_agg(c ORDER BY cand_id) FROM hr.cand c),(SELECT json_agg(s ORDER BY cand_id,skl_cd) FROM hr.cand_skl s),(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");}
    String isolatedSources() {return text("SELECT json_build_array((SELECT json_agg(d ORDER BY dev_id) FROM mst.dev d),(SELECT json_agg(c ORDER BY mth_cst_id) FROM cst.mth_cst c),(SELECT json_agg(a ORDER BY asgn_id) FROM asgn.asgn a),(SELECT json_agg(h ORDER BY cntct_hist_id) FROM hr.cand_cntct_hist h),(SELECT json_agg(m ORDER BY cls_ym) FROM fin.mth_cls m),(SELECT json_agg(m ORDER BY pl_ym) FROM fin.mth_pl m))::text");}
    @Test void anonymousRegistrationMaterializesGeneratedIdentitySkillsAndAuditWithoutUnrelatedWrites() throws Exception {
        String before=isolatedSources();var b=body();b.put("tel_no","010-1234-5678");
        mvc.perform(post("/api/candidates").contentType("application/json").content(json.writeValueAsString(b))).andExpect(status().isOk()).andExpect(jsonPath("$.data.cand_no").value("CAND-0001"));
        mvc.perform(get("/api/candidates/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.skill[0]").value("JAVA")).andExpect(jsonPath("$.data.hope_untprc_amt").value(1000000)).andExpect(jsonPath("$.data.dev_id").isEmpty());
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='hr.cand' AND data_chg_ty_cd='INSERT' AND chg_user_id IS NULL")).isPositive();
        assertThat(text("SELECT aft_val FROM sys.data_chg_hist WHERE clmn_nm='tel_no'")).isEqualTo("***");assertThat(isolatedSources()).isEqualTo(before);
    }
    @Test void sameNameCreatesDistinctIdsAndDoesNotLinkExistingDeveloper() {
        sql("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd) VALUES('CANDTEST','같은 이름','FL')");
        var first=create(body()).row();var second=create(body()).row();assertThat(first.cand_id()).isNotEqualTo(second.cand_id());assertThat(first.cand_no()).isNotEqualTo(second.cand_no());assertThat(second.dev_id()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"hr.cand_skl","sys.data_chg_hist"})
    void childOrAuditFailureRollsBackCandidateSkillsAndNumberRow(String table) {
        String before=snapshot();sql("ALTER TABLE "+table+" ADD CONSTRAINT reject_new CHECK(false) NOT VALID");
        assertThatThrownBy(() -> create(body())).isInstanceOf(RuntimeException.class);assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"cand_nm","job_cd","avail_dt","hope_untprc_amt"})
    void invalidUpdateLeavesCandidateSkillsAndHistoryUnchanged(String field) throws Exception {
        long id=create(body()).row().cand_id();String before=snapshot();var b=body();if(field.equals("hope_untprc_amt"))b.put(field,-1);else b.remove(field);
        mvc.perform(put("/api/candidates/{id}",id).contentType("application/json").content(json.writeValueAsString(b))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void updatePreservesLatestDateTimeIdContactOrderingConversionAndOtherSources() throws Exception {
        long id=create(body()).row().cand_id();
        sql("INSERT INTO hr.cand_cntct_hist(cand_id,cntct_dt,cntct_stts_cd,memo_desc,created_at) VALUES(?,'2026-10-20','DONE','earlier','2026-10-21'),(?,'2026-10-20','HOLD','later','2026-10-22'),(?,'2026-10-20','INTV','tie winner','2026-10-22'),(?,'2026-10-01','NONE','backdated','2026-10-23')",id,id,id,id);
        String before=isolatedSources();var b=body();b.put("cand_nm","수정");b.put("hope_untprc_amt",2000000);b.put("cntct_stts_cd","NONE");b.put("skill",List.of());
        mvc.perform(put("/api/candidates/{id}",id).contentType("application/json").content(json.writeValueAsString(b))).andExpect(status().isOk()).andExpect(jsonPath("$.data.cntct_stts_cd").value("INTV")).andExpect(jsonPath("$.data.last_cntct_dt").value("2026-10-20"));
        assertThat(service.list(Map.of()).items().get(0).row().cntct_stts_cd()).isEqualTo("INTV");assertThat(service.get(id).contacts()).extracting(CandidateModels.CandidateContact::memo).containsExactly("tie winner","later","earlier","backdated");
        assertThat(isolatedSources()).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM hr.cand_skl WHERE cand_id=?",id)).isZero();
        assertThat(text("SELECT bfr_val||'/'||aft_val FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE' AND clmn_nm='hope_untprc_amt'")).isEqualTo("1000000/2000000");
        sql("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd) VALUES('CONVERTED','전환','FL')");long dev=scalar("SELECT dev_id FROM mst.dev WHERE mng_no='CONVERTED'");sql("UPDATE hr.cand SET dev_id=? WHERE cand_id=?",dev,id);
        service.update(id,CandidateInput.parse(json.valueToTree(b)));assertThat(service.get(id).row().cntct_stts_cd()).isEqualTo("SIGNED");assertThat(service.get(id).row().dev_id()).isEqualTo(dev);
    }
    @Test void osToFlUpdateKeepsCandidateNumberAndClearsVendor() {
        sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(50,'V-CAND','VEND','협력사','123-45-67890')");var b=body();b.put("cand_se_cd","OS");b.put("vndr_ptnr_id",50);var first=create(b).row();
        var after=service.update(first.cand_id(),CandidateInput.parse(json.valueToTree(body()))).row();assertThat(after.cand_no()).isEqualTo(first.cand_no());assertThat(after.vndr_ptnr_id()).isNull();
    }
    @Test void countFilteredKpiPaginationAndStrictThirtyDayBoundaryUseSamePopulation() throws Exception {
        sql("INSERT INTO hr.cand(cand_no,cand_nm,cand_se_cd,job_cd,grd_cd,asgn_psbl_dt,hope_untprc_amt,created_at,cntct_stts_cd,use_yn) SELECT 'CAND-'||lpad(i::text,4,'0'),'이름'||i,'FL','BE','SENIOR','2026-11-01',0,CASE WHEN i=1 THEN timestamp '2026-09-23' ELSE timestamp '2026-09-22' END,CASE WHEN i=2 THEN 'INTV' ELSE 'NONE' END,CASE WHEN i=62 THEN 'N' ELSE 'Y' END FROM generate_series(1,62) i");
        String before=snapshot(),sources=isolatedSources();var f=Map.of("job","BE","grade","SENIOR","shortage_month","2026-11","size","20","page","2");
        var page=service.list(f);assertThat(page.total()).isEqualTo(61);assertThat(page.items()).hasSize(20);assertThat(page.kpi().candidate_count()).isEqualTo(61);assertThat(page.kpi().uncontacted_over_30_days_count()).isEqualTo(60);assertThat(page.kpi().interview_count()).isEqualTo(1);
        assertThat(service.list(f)).isEqualTo(page);assertThat(service.list(Map.of("job","NO_MATCH")).total()).isZero();assertThat(service.list(Map.of("job","NO_MATCH")).items()).isEmpty();
        mvc.perform(get("/api/candidates").param("shortage_month","2026-13")).andExpect(status().isBadRequest());service.get(page.items().get(0).row().cand_id());assertThat(snapshot()).isEqualTo(before);assertThat(isolatedSources()).isEqualTo(sources);
    }
    @Test void matchingUsesOnlyOpenActiveSameJobAndAvailableBeforeMonthEnd() {
        long id=create(body()).row().cand_id();sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(50,'C-CAND','CUST','고객','123-45-67890')");
        sql("INSERT INTO bsn.oppt(oppt_id,oppt_no,oppt_nm,cust_ptnr_id,oppt_stts_cd) VALUES(50,'O-50','열림',50,'OPEN'),(51,'O-51','종료',50,'LOST')");
        sql("INSERT INTO bsn.oppt_need(oppt_id,job_cd,bgn_ym,expc_untprc_amt,use_yn) VALUES(50,'BE','2026-11',3000000,'Y'),(50,'FE','2026-11',3000000,'Y'),(50,'BE','2026-10',3000000,'Y'),(51,'BE','2026-11',3000000,'Y'),(50,'BE','2026-11',3000000,'N')");
        var slots=service.get(id).matching_slots();assertThat(slots).hasSize(1);assertThat(slots.get(0).margin_rate()).isEqualByComparingTo("65.00");
    }
}
