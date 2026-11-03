package com.sicost.candidatesops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.WorkforcePlanningAssertions;
import com.sicost.common.workforce.CandidateForecastReader;
import com.sicost.developers.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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

class CandidateContactPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    CandidateContactService service;
    CandidateContactMapper mapper;
    org.springframework.test.web.servlet.MockMvc mvc;
    final ObjectMapper json=new ObjectMapper();
    Clock clock=Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneOffset.UTC);
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/candidatesops/CandidateContactMapper.xml"),new ClassPathResource("mappers/developers/DeveloperMapper.xml"));factory.afterPropertiesSet();
        var template=new SqlSessionTemplate(factory.getObject());mapper=template.getMapper(CandidateContactMapper.class);var developerMapper=template.getMapper(DeveloperMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,CandidateContactService.class,CandidateShortageService.class,DeveloperService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(CandidateContactMapper.class,()->mapper);context.registerBean(DeveloperMapper.class,()->developerMapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));context.registerBean(Clock.class,()->clock);
        context.registerBean(CandidateForecastReader.class,()->filters->List.of(new CandidateForecastReader.Month("2026-11","BE","SR",new java.math.BigDecimal("0.41")),new CandidateForecastReader.Month("2026-12","BE","SR",new java.math.BigDecimal("1.01"))));
        context.refresh();service=context.getBean(CandidateContactService.class);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CandidateContactController(service,context.getBean(CandidateShortageService.class),json)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO hr.cand(cand_id,cand_no,cand_nm,cand_se_cd,job_cd,grd_cd,hope_untprc_amt,asgn_psbl_dt) VALUES(7,'CAND-0007','후보','FL','BE','SR',6000000,'2026-11-01')");
        sql("INSERT INTO hr.cand_skl(cand_id,skl_cd) VALUES(7,'JAVA')");
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s,database);}
    }
    JdbcTemplate jdbc(){return new JdbcTemplate(source);}
    void sql(String query,Object...args){jdbc().update(query,args);}
    long scalar(String query){return jdbc().queryForObject(query,Long.class);}
    String snapshot(String table){return jdbc().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+table+" t",String.class);}
    String sources(){return String.join("|",List.of("hr.cand","hr.cand_cntct_hist","hr.cand_skl","mst.dev","mst.dev_skl","sys.data_chg_hist","cst.mth_cst","asgn.asgn").stream().map(this::snapshot).toList());}
    String contact(String date,String status)throws Exception {return json.writeValueAsString(Map.of("cntct_dt",date,"cntct_stts_cd",status,"memo","내용"));}
    String conversion()throws Exception {return json.writeValueAsString(Map.of("developer",Map.of("dev_nm","같은 이름","emp_se_cd","FL","main_job_cd","BE")));}
    void add(String date,String status)throws Exception {mvc.perform(post("/api/candidates/7/contacts").contentType("application/json").content(contact(date,status))).andExpect(status().isOk());}
    @Test void anonymousContactMaterializesAndRelatedReadShowsLatestActualDate()throws Exception {
        add("2026-10-20","INTV");assertThat(service.detail(7).row().last_cntct_dt()).isEqualTo(LocalDate.of(2026,10,20));assertThat(service.detail(7).row().cntct_stts_cd()).isEqualTo("INTV");assertThat(scalar("SELECT count(*) FROM hr.cand_cntct_hist WHERE rcrd_user_id IS NULL")).isEqualTo(1);
    }
    @Test void backdatedContactOnlyAppendsAndPreservesEveryOriginalField()throws Exception {
        add("2026-10-20","INTV");String candidate=snapshot("hr.cand"),first=snapshot("hr.cand_cntct_hist");
        add("2026-10-10","DONE");assertThat(snapshot("hr.cand")).isEqualTo(candidate);assertThat(scalar("SELECT count(*) FROM hr.cand_cntct_hist")).isEqualTo(2);
        String original=jdbc().queryForObject("SELECT jsonb_agg(to_jsonb(t))::text FROM hr.cand_cntct_hist t WHERE cntct_dt='2026-10-20'",String.class);assertThat(original).isEqualTo(first);
    }
    @Test void equalContactDateUsesRegistrationTimeAndIdentityTieBreaker()throws Exception {
        add("2026-10-20","INTV");add("2026-10-20","HOLD");assertThat(service.detail(7).row().cntct_stts_cd()).isEqualTo("HOLD");assertThat(scalar("SELECT count(*) FROM hr.cand_cntct_hist")).isEqualTo(2);
        sql("UPDATE hr.cand_cntct_hist SET created_at='2026-10-23 00:00:00'");
        add("2026-10-10","DONE");assertThat(service.detail(7).row().cntct_stts_cd()).isEqualTo("HOLD");
    }
    @Test void latestCandidateUpdateFailureRollsBackContactAndAudit()throws Exception {
        String before=sources();sql("ALTER TABLE hr.cand ADD CONSTRAINT reject_contact CHECK(cntct_stts_cd='NONE') NOT VALID");
        assertThatThrownBy(()->service.contact(7,json.readTree(contact("2026-10-20","INTV")))).isInstanceOf(RuntimeException.class);assertThat(sources()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"future","empty","long"}) void invalidContactPreservesAllSources(String rule)throws Exception {
        String before=sources();var b=new LinkedHashMap<String,Object>(Map.of("cntct_dt","2026-10-20","cntct_stts_cd","INTV","memo","내용"));
        if(rule.equals("future"))b.put("cntct_dt","2026-10-24");if(rule.equals("empty"))b.put("memo","");if(rule.equals("long"))b.put("memo","가".repeat(1001));
        mvc.perform(post("/api/candidates/7/contacts").contentType("application/json").content(json.writeValueAsString(b))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(rule.equals("future")?"cntct_dt":"memo"));assertThat(sources()).isEqualTo(before);
    }
    @Test void anonymousConversionRegistersDeveloperAndAuditButNoPayrollCostOrAssignment()throws Exception {
        String cost=snapshot("cst.mth_cst"),assignments=snapshot("asgn.asgn");
        sql("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,main_job_cd) VALUES('EXISTING','후보','FL','BE')");
        mvc.perform(post("/api/candidates/7/convert").contentType("application/json").content(conversion())).andExpect(status().isOk()).andExpect(jsonPath("$.data.candidate.cntct_stts_cd").value("SIGNED")).andExpect(jsonPath("$.data.developer.skill[0]").value("JAVA"));
        long id=service.detail(7).row().dev_id();assertThat(jdbc().queryForObject("SELECT mng_no FROM mst.dev WHERE dev_id=?",String.class,id)).isNotEqualTo("EXISTING");assertThat(scalar("SELECT count(*) FROM mst.dev")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.dev' AND data_chg_ty_cd='INSERT'")).isEqualTo(1);assertThat(snapshot("cst.mth_cst")).isEqualTo(cost);assertThat(snapshot("asgn.asgn")).isEqualTo(assignments);
        add("2026-10-20","DONE");assertThat(service.detail(7).row().cntct_stts_cd()).isEqualTo("SIGNED");
    }
    @Test void conversionLinkFailureRollsBackDeveloperChildrenCandidateAndAudit()throws Exception {
        String before=sources();sql("ALTER TABLE hr.cand ADD CONSTRAINT reject_link CHECK(dev_id IS NULL) NOT VALID");
        assertThatThrownBy(()->service.convert(7,json.readTree(conversion()))).isInstanceOf(RuntimeException.class);assertThat(sources()).isEqualTo(before);
    }
    @Test void missingEmployeeTypeLeavesCandidateAndDeveloperUnchanged()throws Exception {
        String before=sources();mvc.perform(post("/api/candidates/7/convert").contentType("application/json").content("{\"developer\":{}}" )).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("emp_se_cd"));assertThat(sources()).isEqualTo(before);
    }
    @Test void osMissingVendorLeavesCandidateAndDeveloperUnchanged()throws Exception {
        sql("UPDATE hr.cand SET cand_se_cd='OS' WHERE cand_id=7");String before=sources();mvc.perform(post("/api/candidates/7/convert").contentType("application/json").content(conversion())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("vndr_ptnr_id"));assertThat(sources()).isEqualTo(before);
    }
    @Test void concurrentConversionSerializesAndCreatesExactlyOneDeveloper()throws Exception {
        String body=conversion();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Integer> action=()->{start.await();return mvc.perform(post("/api/candidates/7/convert").contentType("application/json").content(body)).andReturn().getResponse().getStatus();};
            var first=pool.submit(action);var second=pool.submit(action);start.countDown();assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);assertThat(scalar("SELECT count(*) FROM mst.dev")).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void shortageCountsDistinctEligibleCandidatesAndLeavesClosedSourcesAndHistoryUntouched()throws Exception {
        sql("INSERT INTO hr.cand(cand_no,cand_nm,cand_se_cd,job_cd,grd_cd,asgn_psbl_dt,cntct_stts_cd,use_yn) VALUES ('CAND-0010','eligible','FL','BE','SR','2026-12-31','DONE','Y'),('CAND-0011','hold','FL','BE','SR','2026-11-01','HOLD','Y'),('CAND-0012','signed','FL','BE','SR','2026-11-01','SIGNED','Y'),('CAND-0013','disabled','FL','BE','SR','2026-11-01','NONE','N'),('CAND-0014','late','FL','BE','SR','2027-01-01','NONE','Y'),('CAND-0015','grade','FL','BE','JR','2026-11-01','NONE','Y')");
        var closed=WorkforcePlanningAssertions.captureClosedSources(jdbc());String before=sources();
        mvc.perform(get("/api/candidates/shortages").param("job","BE").param("grade","SR")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].candidate_count").value(2)).andExpect(jsonPath("$.data.items[0].maximum_shortage_mm").value(1.01)).andExpect(jsonPath("$.data.items[0].maximum_shortage_people").value(2));
        assertThat(sources()).isEqualTo(before);closed.assertUnchanged(jdbc());
    }
}
