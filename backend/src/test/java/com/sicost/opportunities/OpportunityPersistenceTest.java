package com.sicost.opportunities;

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
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class OpportunityPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    OpportunityService service;
    org.springframework.test.web.servlet.MockMvc mvc;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/opportunities/OpportunityMapper.xml"));factory.afterPropertiesSet();
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(OpportunityMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,OpportunityService.class,CurrentUser.class);
        context.registerBean(OpportunityMapper.class,()->mapper);context.registerBean(ObjectMapper.class,()->json);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(OpportunityService.class);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new OpportunityController(service,json)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(10001,'C-OPPORTUNITY','CUST','기회 고객','123-45-67890')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,main_job_cd,grd_cd) VALUES(10001,'K-OPPORTUNITY1','원가 인력1','FT','BE','MID'),(10002,'K-OPPORTUNITY2','원가 인력2','FT','BE','MID')");
        sql("INSERT INTO cmn.cd_dtl(cd_grp_id,dtl_cd,cd_nm) VALUES('GRADE','MID','중급') ON CONFLICT DO NOTHING");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(10001,'2026-09','FT',600000),(10002,'2026-09','FT',600000)");
    }
    @AfterEach void removeIsolatedDatabase()throws Exception{if(context!=null)context.close();if(source==null)return;try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s,database);}}
    void sql(String sql,Object...args){new org.springframework.jdbc.core.JdbcTemplate(source).update(sql,args);}
    long scalar(String sql,Object...args){return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql,Long.class,args);}
    String value(String sql){return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql,String.class);}
    Map<String,Object> body(){var b=new LinkedHashMap<String,Object>();b.put("oppt_nm","기회");b.put("cust_ptnr_id",10001);b.put("bsn_part_id",1);b.put("expc_amt",3000000);b.put("cntrct_ty_cd","MO");b.put("bgn_ym","2026-11");b.put("mth_cnt",3);b.put("needs",List.of(new LinkedHashMap<>(Map.of("job_cd","BE","grd_cd","MID","psn_cnt",2,"bgn_ym","2026-11","mth_cnt",3,"mth_mm_qty",0.50,"expc_untprc_amt",1000000))));return b;}
    OpportunityModels.Detail create(){return service.create(OpportunityInput.parse(json.valueToTree(body())));}
    Map<String,Object> editing(OpportunityModels.Detail row){var b=body();var n=new LinkedHashMap<String,Object>((Map<String,Object>)((List<?>)b.get("needs")).get(0));n.put("need_id",row.needs().get(0).row().need_id());b.put("needs",List.of(n));return b;}
    String snapshot(){return value("SELECT json_build_array((SELECT json_agg(o ORDER BY oppt_id) FROM bsn.oppt o),(SELECT json_agg(n ORDER BY need_id) FROM bsn.oppt_need n),(SELECT json_agg(h ORDER BY aprv_hist_id) FROM bsn.quot_aprv_hist h),(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),(SELECT json_agg(t ORDER BY tmpr_asgn_id) FROM asgn.tmpr_asgn t),(SELECT json_agg(c ORDER BY mth_cst_id) FROM cst.mth_cst c),(SELECT json_agg(c ORDER BY cls_ym) FROM fin.mth_cls c))::text");}
    @Test void anonymousHttpCreateReadbackQuoteAndAudit()throws Exception{mvc.perform(post("/api/opportunities").contentType("application/json").content(json.writeValueAsString(body()))).andExpect(status().isOk()).andExpect(jsonPath("$.data.quote.expc_sls_amt").value(3000000)).andExpect(jsonPath("$.data.quote.expc_cst_amt").value(1800000)).andExpect(jsonPath("$.data.quote.expc_mrgn_rt").value(40));assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bsn.oppt' AND chg_user_id IS NULL")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM bsn.oppt_need WHERE psn_mth_cst_amt=600000 AND use_yn='Y'")).isEqualTo(1);}
    @Test void readFiltersKpiAndDetailAreSideEffectFree()throws Exception{var r=create();String before=snapshot();mvc.perform(get("/api/opportunities").param("q","기회").param("from","2026-11").param("page","1").param("size","20")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.kpi.needed_people").value(2));mvc.perform(get("/api/opportunities/{id}",r.oppt_id())).andExpect(status().isOk()).andExpect(jsonPath("$.data.quote.expc_mrgn_rt").value(40));assertThat(snapshot()).isEqualTo(before);}
    @Test void stableNeedIdAndOmittedRowReleasePreserveHistory()throws Exception{var r=create();long need=r.needs().get(0).row().need_id();sql("INSERT INTO asgn.tmpr_asgn(need_id,dev_id,bgn_ym,end_ym) VALUES(?,10001,'2026-11','2026-12')",need);var b=editing(r);b.put("bsn_stg_cd","PROP");var next=service.update(r.oppt_id(),OpportunityInput.parse(json.valueToTree(b)));assertThat(next.win_prblt_rt()).isEqualByComparingTo("30");assertThat(next.needs().get(0).row().need_id()).isEqualTo(need);b.put("needs",List.of());b.put("expc_amt",0);service.update(r.oppt_id(),OpportunityInput.parse(json.valueToTree(b)));assertThat(value("SELECT use_yn FROM bsn.oppt_need")).isEqualTo("N");assertThat(value("SELECT tmpr_asgn_stts_cd FROM asgn.tmpr_asgn")).isEqualTo("RELEASED");}
    @Test void failureDuringQuoteNeedSaveRollsBackHeaderNeedsAndAudit(){var r=create();String before=snapshot();sql("ALTER TABLE bsn.oppt_need ADD CONSTRAINT reject_edit CHECK(psn_cnt=2) NOT VALID");var b=editing(r);var n=new LinkedHashMap<String,Object>((Map<String,Object>)((List<?>)b.get("needs")).get(0));n.put("psn_cnt",3);b.put("needs",List.of(n));b.put("amount_difference_reason","추가 인력");assertThatThrownBy(()->service.update(r.oppt_id(),OpportunityInput.parse(json.valueToTree(b)))).isInstanceOf(RuntimeException.class);assertThat(snapshot()).isEqualTo(before);}
    @Test void auditFailureRollsBackRegistration(){String before=snapshot();sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_new CHECK(false) NOT VALID");assertThatThrownBy(this::create).isInstanceOf(RuntimeException.class);assertThat(snapshot()).isEqualTo(before);}
    @Test void closedUpdateAndDifferenceValidationAreAtomic(){var r=create();var b=editing(r);b.put("expc_amt",1);String before=snapshot();assertThatThrownBy(()->service.update(r.oppt_id(),OpportunityInput.parse(json.valueToTree(b)))).isInstanceOf(ApiException.class);assertThat(snapshot()).isEqualTo(before);sql("UPDATE bsn.oppt SET oppt_stts_cd='LOST' WHERE oppt_id=?",r.oppt_id());before=snapshot();assertThatThrownBy(()->service.update(r.oppt_id(),OpportunityInput.parse(json.valueToTree(editing(r))))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("OPPORTUNITY_CLOSED"));assertThat(snapshot()).isEqualTo(before);}
    @Test void staleWarningStrictlyGreaterThanFourteenSeoulDays(){var r=create();sql("ALTER TABLE bsn.oppt DISABLE TRIGGER USER");sql("UPDATE bsn.oppt SET updated_at=NULL,created_at='2026-10-09 23:59' WHERE oppt_id=?",r.oppt_id());assertThat(service.get(r.oppt_id()).stale_warning()).isFalse();sql("UPDATE bsn.oppt SET created_at='2026-10-08 23:59' WHERE oppt_id=?",r.oppt_id());assertThat(service.get(r.oppt_id()).stale_warning()).isTrue();}
}
