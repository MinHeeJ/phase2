package com.sicost.projects;

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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class ProjectPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ProjectService service;
    ProjectMapper mapper;
    org.springframework.test.web.servlet.MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();
    Clock clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null,"Test database connection settings are absent");
        database = "sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try (Connection admin = DriverManager.getConnection(adminUrl,user,password); var s=admin.createStatement()) { com.sicost.support.TestDatabaseDdl.create(s, database); }
        source = new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/projects/ProjectMapper.xml")); factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ProjectMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class,ProjectService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(ProjectMapper.class,() -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,() -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,() -> clock); context.refresh(); service = context.getBean(ProjectService.class);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ProjectController(service,json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(1,'C-TEST','CUST','고객사','123-45-67890')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'K-0001','개발자','FT'),(2,'K-0002','두번째','FT'),(3,'K-0003','세번째','FT')");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (Connection admin=DriverManager.getConnection(adminUrl,user,password); var s=admin.createStatement()) { com.sicost.support.TestDatabaseDdl.drop(s, database); }
    }
    long scalar(String sql, Object... args) { return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql, Long.class, args); }
    String value(String sql) throws Exception { try (var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)) { assertThat(r.next()).isTrue();return r.getString(1); } }
    void sql(String sql, Object... args) { new org.springframework.jdbc.core.JdbcTemplate(source).update(sql, args); }
    Map<String,Object> body() {
        var b = new LinkedHashMap<String,Object>();
        b.put("prj_nm","프로젝트");b.put("cntrct_ty_cd","MO");b.put("prj_stts_cd","RUN");
        b.put("cust_ptnr_id",1);b.put("bsn_part_id",1);b.put("init_cntrct_amt",500000000L);
        b.put("cntrct_bgn_dt","2026-03-01");b.put("cntrct_end_dt","2026-12-31");b.put("prj_skl",List.of("JAVA"));return b;
    }
    ProjectModels.ProjectSave input(Map<String,Object> b, boolean update) { return ProjectInput.parse(json.valueToTree(b),update); }
    ProjectModels.ProjectDetail create(Map<String,Object> b) { return service.create(input(b,false)); }
    ProjectModels.ProjectDetail update(long id,Map<String,Object> b) { return service.update(id,input(b,true)); }
    String snapshot() throws Exception { return value("SELECT json_build_array((SELECT json_agg(p ORDER BY prj_id) FROM prj.prj p),(SELECT json_agg(s ORDER BY prj_id,skl_cd) FROM prj.prj_skl s),(SELECT json_agg(h ORDER BY part_hist_id) FROM prj.prj_part_hist h),(SELECT json_agg(p ORDER BY phs_id) FROM prj.phs p),(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text"); }
    @Test void moGeneratedIdDetailChildrenAndAuditMaterialize() throws Exception {
        mvc.perform(post("/api/projects").contentType("application/json").content(json.writeValueAsString(body())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.prj_no").value("P-2601"));
        var p=service.get("P-2601");var read=service.get(p.row().prj_id());
        assertThat(read.row().prj_no()).isEqualTo("P-2601");assertThat(read.row().cur_cntrct_amt()).isEqualTo(500000000L);
        assertThat(read.row().trgt_cst_amt()).isEqualTo(400000000L);assertThat(read.row().partName()).isEqualTo("본사(직접 영업)");assertThat(read.prj_skl()).containsExactly("JAVA");
        assertThat(value("SELECT aply_bgn_ym FROM prj.prj_part_hist")).isEqualTo("2026-03");assertThat(scalar("SELECT count(*) FROM prj.phs")).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.prj' AND obj_rec_id=? AND data_chg_ty_cd='INSERT' AND chg_user_id IS NULL", Long.toString(p.row().prj_id()))).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM prj.prj WHERE cur_end_dt=cntrct_end_dt AND use_yn='Y'")).isEqualTo(1);
    }
    @Test void businessDuplicateNamesCreateDistinctPlanNumbers() throws Exception {
        var b=body();b.put("prj_stts_cd","PLAN");
        mvc.perform(post("/api/projects").contentType("application/json").content(json.writeValueAsString(b)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.prj_no").value("P-2601"));
        mvc.perform(post("/api/projects").contentType("application/json").content(json.writeValueAsString(b)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.prj_no").value("P-2602"));
        assertThat(scalar("SELECT count(DISTINCT prj_no) FROM prj.prj WHERE prj_nm='프로젝트' AND prj_stts_cd='PLAN'")).isEqualTo(2);
    }
    @Test void projectPostWaitsForAdvisoryNumberLockThenCommitsUniqueNumbers() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var started=new java.util.concurrent.CountDownLatch(2);
        String payload=json.writeValueAsString(body());
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT pg_advisory_xact_lock(hashtext('prj.prj_no'))");
            var first=executor.submit(() -> {
                started.countDown();
                return mvc.perform(post("/api/projects").contentType("application/json").content(payload))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
            });
            var second=executor.submit(() -> {
                started.countDown();
                return mvc.perform(post("/api/projects").contentType("application/json").content(payload))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
            });
            assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> first.get(200,java.util.concurrent.TimeUnit.MILLISECONDS))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(scalar("SELECT count(*) FROM prj.prj")).isZero();
            connection.commit();
            first.get(10,java.util.concurrent.TimeUnit.SECONDS);second.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(scalar("SELECT count(DISTINCT prj_no) FROM prj.prj")).isEqualTo(2);
            assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.prj' AND data_chg_ty_cd='INSERT'")).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }
    @Test void businessPlanToHoldUpdatesSkillsAndAuditsWithoutReplacingPhases() throws Exception {
        var b=body();b.put("prj_stts_cd","PLAN");long id=create(b).row().prj_id();
        String phase=value("SELECT json_agg(p ORDER BY phs_id)::text FROM prj.phs p");
        b.put("prj_stts_cd","HOLD");b.put("prj_skl",List.of());
        mvc.perform(put("/api/projects/{id}",id).contentType("application/json").content(json.writeValueAsString(b)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.prj_stts_cd").value("HOLD"));
        assertThat(scalar("SELECT count(*) FROM prj.prj_skl")).isZero();
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE' AND clmn_nm='prj_stts_cd' AND bfr_val='PLAN' AND aft_val='HOLD'")).isEqualTo(1);
        assertThat(value("SELECT json_agg(p ORDER BY phs_id)::text FROM prj.phs p")).isEqualTo(phase);
    }
    @Test void internalNullHistoryAndSevenPhases() throws Exception { var b=body();b.put("cntrct_ty_cd","IN");var p=create(b).row();assertThat(p.cust_ptnr_id()).isNull();assertThat(p.bsn_part_id()).isNull();assertThat(p.cur_cntrct_amt()).isNull();assertThat(p.trgt_cst_amt()).isNull();assertThat(scalar("SELECT count(*) FROM prj.phs")).isEqualTo(7);assertThat(scalar("SELECT count(*) FROM prj.prj_part_hist WHERE bsn_part_id IS NULL")).isEqualTo(1); }
    @Test void phaseInclusiveHundredDaysHasFifteenAnalysisAndRemainderInLast() throws Exception { var b=body();b.put("cntrct_ty_cd","TK");b.put("cntrct_bgn_dt","2026-01-01");b.put("cntrct_end_dt","2026-04-10");create(b);assertThat(scalar("SELECT plan_end_dt-plan_bgn_dt+1 FROM prj.phs WHERE phs_nm='분석'")).isEqualTo(15);assertThat(value("SELECT max(plan_end_dt)::text FROM prj.phs")).isEqualTo("2026-04-10");assertThat(scalar("SELECT sum(plan_end_dt-plan_bgn_dt+1) FROM prj.phs")).isEqualTo(100); }
    @Test void singleDayPreservesZeroDayRows() throws Exception { var b=body();b.put("prj_nm","가".repeat(100));b.put("cntrct_end_dt","2026-03-01");create(b);assertThat(scalar("SELECT count(*) FROM prj.phs WHERE plan_bgn_dt IS NULL AND plan_end_dt IS NULL")).isEqualTo(4);assertThat(value("SELECT max(plan_end_dt)::text FROM prj.phs")).isEqualTo("2026-03-01"); }
    @Test void duplicatesAndFloorAndNumericMaximumSuffix() throws Exception { var b=body();b.put("init_cntrct_amt",123456789);create(b);create(b);sql("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,cntrct_bgn_dt) VALUES('P-2699','기존','IN','2026-01-01'),('P-26100','기존','IN','2026-01-01')");var p=create(b).row();assertThat(p.prj_no()).isEqualTo("P-26101");assertThat(p.trgt_cst_amt()).isEqualTo(98765431L);assertThat(service.list(Map.of("q","프로젝트")).total()).isEqualTo(3); }
    @ParameterizedTest @ValueSource(strings={"prj.prj_skl","prj.prj_part_hist","prj.phs","sys.data_chg_hist"})
    void childOrAuditFailureRollsBackWholeRegistration(String table) throws Exception {
        String before=snapshot();
        sql(switch (table) {
            case "prj.prj_skl" -> "ALTER TABLE prj.prj_skl ADD CONSTRAINT reject_new CHECK(false) NOT VALID";
            case "prj.prj_part_hist" -> "ALTER TABLE prj.prj_part_hist ADD CONSTRAINT reject_new CHECK(false) NOT VALID";
            case "prj.phs" -> "ALTER TABLE prj.phs ADD CONSTRAINT reject_new CHECK(false) NOT VALID";
            case "sys.data_chg_hist" -> "ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_new CHECK(false) NOT VALID";
            default -> throw new IllegalArgumentException("Unsupported test table");
        });
        assertThatThrownBy(() -> create(body())).isInstanceOf(RuntimeException.class);assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"name","dates","part"}) void validationLeavesAllTablesUnchanged(String rule) throws Exception {String before=snapshot();var b=body();if(rule.equals("name"))b.put("prj_nm","가".repeat(101));if(rule.equals("dates"))b.put("cntrct_end_dt","2026-02-28");if(rule.equals("part"))b.remove("bsn_part_id");assertThatThrownBy(() -> create(b)).isInstanceOf(ApiException.class);assertThat(snapshot()).isEqualTo(before);}
    @Test void listYearAllAndFiltersDefaultStatusIndependentCountDistinctPeople() throws Exception {
        long id=create(body()).row().prj_id();
        sql("UPDATE prj.prj SET bsn_part_id=NULL WHERE prj_id=?", id);
        sql("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cntrct_bgn_dt,cntrct_end_dt,use_yn) VALUES('P-2501','작년','IN','RUN','2025-01-01','2025-12-31','Y'),('P-2502','겹침','MO','END','2025-01-01','2026-01-01','Y'),('P-2602','내부','IN','RUN','2026-01-01','2026-12-31','Y'),('P-2603','중지','MO','RUN','2026-01-01','2026-12-31','N')");
        sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) SELECT 'A-'||i,?,CASE WHEN i=4 THEN 1 ELSE i END,'BE','2026-10-01','2026-10-31' FROM generate_series(1,4) i", id);
        var page=service.list(Map.of());assertThat(page.total()).isEqualTo(2);assertThat(page.unassignedCount()).isEqualTo(2);assertThat(service.get(id).row().assignmentCount()).isEqualTo(3);
        var filtered=service.list(Map.of("status","END","part","NONE","type","MO","q","겹침"));assertThat(filtered.total()).isEqualTo(1);assertThat(filtered.items()).hasSize(1);assertThat(filtered.unassignedCount()).isEqualTo(2);
        assertThat(service.list(Map.of("part","1")).total()).isZero();assertThat(service.list(Map.of("part","NONE")).total()).isEqualTo(1);
    }
    @Test void sortingPrecedesPaginationAndDisabledNeverAppears() throws Exception {
        sql("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cntrct_bgn_dt,cntrct_end_dt,use_yn) SELECT 'P-'||(2600+i),'이름'||lpad(i::text,3,'0'),'IN','RUN',date '2026-01-01'+i,date '2026-12-31',CASE WHEN i=62 THEN 'N' ELSE 'Y' END FROM generate_series(1,62) i");
        var first=service.list(Map.of());assertThat(first.items()).hasSize(50);assertThat(first.total()).isEqualTo(61);
        assertThat(first.items().get(0).prj_no()).isEqualTo("P-2661");
        var next=service.list(Map.of("page","1","sort","prj_nm,asc"));assertThat(next.items()).hasSize(11);assertThat(next.items().get(0).prj_nm()).isEqualTo("이름051");
    }
    @Test void nameOnlyUpdateAuditNoopAndLastSaveWins() throws Exception {
        long id=create(body()).row().prj_id();var b=body();b.put("prj_nm","첫 이름");update(id,b);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).isEqualTo(1);
        assertThat(value("SELECT bfr_val||'/'||aft_val FROM sys.data_chg_hist WHERE clmn_nm='prj_nm'")).isEqualTo("프로젝트/첫 이름");String before=snapshot();update(id,b);assertThat(snapshot()).isEqualTo(before);
        b.put("prj_nm","마지막 이름");update(id,b);assertThat(service.get(id).row().prj_nm()).isEqualTo("마지막 이름");
    }
    @Test void manualTargetAndSourceOwnedValuesAndAmountEndSync() throws Exception {
        long id=create(body()).row().prj_id();var b=body();b.put("trgt_cst_amt",123L);b.put("prj_no","P-9999");b.put("cur_cntrct_amt",42);b.put("bsn_part_id",999);
        update(id,b);var r=service.get(id).row();assertThat(r.trgt_cst_amt()).isEqualTo(123L);assertThat(r.cur_cntrct_amt()).isEqualTo(500000000L);assertThat(r.bsn_part_id()).isEqualTo(1);assertThat(r.prj_no()).isEqualTo("P-2601");
        b=body();b.put("init_cntrct_amt",600000000L);b.put("cntrct_end_dt","2027-01-01");update(id,b);r=service.get(id).row();assertThat(r.cur_cntrct_amt()).isEqualTo(600000000L);assertThat(r.cur_end_dt()).isEqualTo(LocalDate.of(2027,1,1));
    }
    @ParameterizedTest @ValueSource(strings={"bill","allocation","assignment"}) void eachTypeLockSourceIs409AndNoMutation(String kind) throws Exception {
        long id=create(body()).row().prj_id();
        if(kind.equals("bill"))sql("INSERT INTO prj.bill_stg(prj_id,stg_odr_no,bill_stg_nm,sply_amt) VALUES(?,1,'착수금',1)", id);
        if(kind.equals("allocation"))sql("INSERT INTO prj.tk_aloc_line(prj_id,aloc_stg_cd,line_ty_cd,item_nm) VALUES(?,'EST','ITEM','경비')", id);
        if(kind.equals("assignment"))sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES('A1',?,1,'BE','2026-01-01','2026-12-31')", id);
        String before=snapshot();var b=body();b.put("cntrct_ty_cd","TK");assertThatThrownBy(() -> update(id,b)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("TYPE_LOCKED"));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void periodAndTypeEditPreservePhasesAndAllPlanRows() throws Exception {long id=create(body()).row().prj_id();String phase=value("SELECT json_agg(p ORDER BY phs_id)::text FROM prj.phs p");sql("INSERT INTO prj.psn_plan(prj_id,plan_ym,job_cd,need_mm_qty) VALUES(?,'2026-12','BE',1)", id);var b=body();b.put("cntrct_ty_cd","TK");b.put("cntrct_end_dt","2026-11-30");update(id,b);assertThat(value("SELECT json_agg(p ORDER BY phs_id)::text FROM prj.phs p")).isEqualTo(phase);assertThat(scalar("SELECT count(*) FROM prj.psn_plan")).isEqualTo(1);}
    @Test void updateAuditFailureRollsBackColumnsAndSkills() throws Exception {long id=create(body()).row().prj_id();String before=snapshot();sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_update CHECK(data_chg_ty_cd!='UPDATE') NOT VALID");var b=body();b.put("prj_nm","실패");b.put("prj_skl",List.of());assertThatThrownBy(() -> update(id,b)).isInstanceOf(RuntimeException.class);assertThat(snapshot()).isEqualTo(before);}
    @Test void disabledCanonicalGetBypassesListButCannotWrite() throws Exception {long id=create(body()).row().prj_id();sql("UPDATE prj.prj SET use_yn='N' WHERE prj_id=?", id);assertThat(service.list(Map.of()).items()).isEmpty();assertThat(service.get("P-2601").row().use_yn()).isEqualTo("N");String before=snapshot();assertThatThrownBy(() -> update(id,body())).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.field()).isEqualTo("use_yn"));assertThat(snapshot()).isEqualTo(before);}
}
