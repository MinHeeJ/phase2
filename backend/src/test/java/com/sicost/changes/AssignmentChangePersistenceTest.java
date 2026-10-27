package com.sicost.changes;

import static com.sicost.changes.AssignmentChangeModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import com.sicost.common.validation.AssignmentRules;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AssignmentChangePersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;
    AnnotationConfigApplicationContext context;
    AssignmentChangeService service;
    AssignmentChangeMapper mapper;
    MockMvc mvc;
    long assignment, project, developer;
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
        project=fixtures.createProject(); developer=fixtures.createDeveloper();
        jdbc.update("UPDATE prj.prj SET cntrct_ty_cd='MO' WHERE prj_id=?",project);
        assignment=jdbc.queryForObject("SELECT nextval('asgn.seq_asgn_id')",Long.class);
        jdbc.update("""
            INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,plan_end_dt,
              ext_psblty_cd,created_at,updated_at)
            VALUES(?,?,?,?,'BE','2026-09-01','2026-12-31','2026-12-31','M',
              TIMESTAMP '2026-10-13 12:00:00.123456',TIMESTAMP '2026-10-13 12:00:00.123456')
            """,assignment,AssignmentRules.allocated(assignment),project,developer);
        jdbc.update("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(?,'2026-09-01',7000000)",assignment);
        jdbc.update("""
            INSERT INTO asgn.asgn_mth(asgn_id,work_ym,auto_mm_qty,mm_qty,mnl_yn)
            VALUES(?,'2026-09',1,1,'N'),(?,'2026-10',1,1,'N'),(?,'2026-11',1,0.50,'Y'),(?,'2026-12',1,1,'N')
            """,assignment,assignment,assignment,assignment);
        append("START");
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/changes/AssignmentChangeMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"));
        factory.afterPropertiesSet(); var session=new SqlSessionTemplate(factory.getObject());
        mapper=session.getMapper(AssignmentChangeMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(AssignmentTestSupport.Transactions.class,AssignmentChangeService.class,
            AssignmentConcurrencyService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(AssignmentChangeMapper.class,()->mapper);
        context.registerBean(AssignmentConcurrencyMapper.class,()->session.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->AssignmentTestSupport.CLOCK);
        context.refresh(); service=context.getBean(AssignmentChangeService.class);
        mvc=MockMvcBuilders.standaloneSetup(new AssignmentChangeController(service,fixtures.json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close(); if(fixtures!=null) fixtures.close(); if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    LocalDateTime token() {
        return jdbc.queryForObject("SELECT coalesce(a.updated_at,a.created_at) FROM asgn.asgn a WHERE a.asgn_id=?",LocalDateTime.class,assignment);
    }
    String snapshot() {
        return jdbc.queryForObject("""
            SELECT json_build_object(
              'assignment',(SELECT json_agg(a ORDER BY a.asgn_id) FROM asgn.asgn a),
              'rates',(SELECT json_agg(r ORDER BY r.asgn_id,r.aply_bgn_dt) FROM asgn.untprc_hist r),
              'months',(SELECT json_agg(m ORDER BY m.asgn_id,m.work_ym) FROM asgn.asgn_mth m),
              'history',(SELECT json_agg(h ORDER BY h.asgn_chg_id) FROM asgn.asgn_chg_hist h))::text
            """,String.class);
    }
    String financialSnapshot() {
        return jdbc.queryForObject("""
            SELECT json_build_object('end',(SELECT a.end_dt FROM asgn.asgn a WHERE a.asgn_id=?),
              'rates',(SELECT json_agg(r ORDER BY r.aply_bgn_dt) FROM asgn.untprc_hist r WHERE r.asgn_id=?),
              'months',(SELECT json_agg(m ORDER BY m.work_ym) FROM asgn.asgn_mth m WHERE m.asgn_id=?))::text
            """,String.class,assignment,assignment,assignment);
    }
    long append(String type) {
        return jdbc.queryForObject("INSERT INTO asgn.asgn_chg_hist(asgn_id,asgn_chg_ty_cd,aply_dt,bfr_val,aft_val) VALUES(?,?,'2026-10-14','이전','이후') RETURNING asgn_chg_id",Long.class,assignment,type);
    }
    Map<String,Object> input(String type) {
        var n=new LinkedHashMap<String,Object>();
        n.put("updated_at",token().toString()); n.put("asgn_chg_ty_cd",type); n.put("aply_dt","2026-10-14");
        switch(type) {
            case "EXTEND" -> { n.put("aply_dt",jdbc.queryForObject("SELECT (a.end_dt+1)::text FROM asgn.asgn a WHERE a.asgn_id=?",String.class,assignment)); n.put("end_dt","2027-03-31"); }
            case "EARLY_END" -> { n.put("aply_dt","2026-11-15"); n.put("rsn_cd","PERSON"); }
            case "RATE" -> { n.put("aply_dt","2026-12-01"); n.put("work_ym","2026-12"); n.put("bill_untprc_amt",7200000L); }
            case "EXT_CHANCE" -> n.put("ext_psblty_cd","H");
            case "PERIOD" -> n.put("asgn_se_cd","ADD");
            default -> throw new IllegalArgumentException(type);
        }
        return n;
    }
    SaveResult save(Map<String,Object> input) { return service.create(assignment,AssignmentChangeInput.parse(fixtures.json.valueToTree(input))); }
    FixInput correction() { return new FixInput(token(),"바른 내용","FIXERR",null); }
    long count(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM asgn.asgn_chg_hist h WHERE h.asgn_id=? AND h.asgn_chg_ty_cd=?",Long.class,assignment,type);
    }
    void conflict(Runnable action,String code) {
        String before=snapshot();
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(code));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void anonymousListFiltersCountAndNeverMutates() throws Exception {
        save(input("EXTEND")); save(input("RATE")); String before=snapshot();
        mvc.perform(get("/api/assignments/{id}/changes",assignment)).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get("/api/assignments/{id}/changes",assignment).param("type","EXTEND"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/assignments/{id}/changes",assignment).param("type","REPLACE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void anonymousExtensionUpdatesHeaderGeneratesMonthsAndKeepsHistoryAndManualValues() throws Exception {
        var old=service.list(assignment,null).items();
        mvc.perform(post("/api/assignments/{id}/changes",assignment).contentType("application/json")
            .content(fixtures.json.writeValueAsBytes(input("EXTEND")))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignment.end_dt").value("2027-03-31"));
        assertThat(count("EXTEND")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT m.work_ym FROM asgn.asgn_mth m WHERE m.asgn_id=? AND m.work_ym>='2027-01' ORDER BY m.work_ym",String.class,assignment))
            .containsExactly("2027-01","2027-02","2027-03");
        assertThat(jdbc.queryForObject("SELECT m.mm_qty FROM asgn.asgn_mth m WHERE m.asgn_id=? AND m.work_ym='2026-11'",String.class,assignment)).isEqualTo("0.50");
        assertThat(service.list(assignment,"START").items()).containsExactlyElementsOf(old);
    }
    @Test void earlyDepartureRemovesOpenDecemberAndKeepsActiveStatus() {
        var r=save(input("EARLY_END"));
        assertThat(r.assignment().header().end_dt()).isEqualTo(LocalDate.parse("2026-11-15"));
        assertThat(r.assignment().header().asgn_stts_cd()).isEqualTo("ACTIVE");
        assertThat(r.assignment().months()).noneMatch(m -> m.work_ym().equals("2026-12"));
        assertThat(r.change().bfr_val()).isEqualTo("2026-12-31");
        assertThat(r.change().aft_val()).isEqualTo("2026-11-15");
        assertThat(r.change().rsn_nm()).isEqualTo("인력 사정");
    }
    @Test void earlyDepartureWithoutReasonRollsBack() {
        var n=input("EARLY_END"); n.remove("rsn_cd"); conflict(()->save(n),"INVALID_INPUT");
    }
    @Test void equalExtensionLeavesAllTablesUnchanged() {
        var n=input("EXTEND"); n.put("end_dt","2026-12-31"); conflict(()->save(n),"INVALID_INPUT");
    }
    @Test void rateUpsertKeepsOtherDatesAndUsesCurrentMonthRate() {
        save(input("RATE")); var n=input("RATE"); n.put("bill_untprc_amt",7400000L); var r=save(n);
        assertThat(r.assignment().rates()).containsExactly(new RateRow(LocalDate.parse("2026-09-01"),7000000L),new RateRow(LocalDate.parse("2026-12-01"),7400000L));
        assertThat(r.change().bfr_val()).isEqualTo("7,200,000원");
    }
    @Test void equalRateRejectsWithoutNewHistory() {
        var n=input("RATE"); n.put("bill_untprc_amt",7000000L); conflict(()->save(n),"INVALID_INPUT");
    }
    @Test void sameChanceAfterFirstSaveRejectsAndKeepsSingleHistory() {
        save(input("EXT_CHANCE")); conflict(()->save(input("EXT_CHANCE")),"INVALID_INPUT"); assertThat(count("EXT_CHANCE")).isEqualTo(1);
    }
    @Test void staleSecondSaveCannotLeakCurrentOrHistory() {
        var one=input("EXTEND"); save(one); conflict(()->save(one),"STALE_ASSIGNMENT");
    }
    @Test void concurrentSameTokenHasExactlyOneCommit() throws Exception {
        var n=input("EXT_CHANCE"); var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<String> action=()-> { start.await(); try { save(n); return "OK"; } catch(ApiException e) { return e.code(); } };
            var a=executor.submit(action); var b=executor.submit(action); start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","STALE_ASSIGNMENT");
            assertThat(count("EXT_CHANCE")).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void monthFailureRollsBackHeaderRatesMonthsAndHistory() {
        jdbc.execute("ALTER TABLE asgn.asgn_mth ADD CONSTRAINT reject_assignment_month CHECK(false) NOT VALID");
        String before=snapshot(); assertThatThrownBy(()->save(input("EXTEND"))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void historyFailureRollsBackRateAndParentTouch() {
        jdbc.execute("ALTER TABLE asgn.asgn_chg_hist ADD CONSTRAINT reject_assignment_history CHECK(false) NOT VALID");
        String before=snapshot(); assertThatThrownBy(()->save(input("RATE"))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void correctionIsAtomicAndFinancialValuesDoNotChange() throws Exception {
        var changed=save(input("EXTEND")); String before=financialSnapshot();
        mvc.perform(post("/api/assignments/{id}/changes/{chg}/fix",assignment,changed.change().asgn_chg_id())
            .contentType("application/json").content(fixtures.json.writeValueAsBytes(correction())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.target.fixed_yn").value("Y"))
            .andExpect(jsonPath("$.data.fix.fix_trgt_chg_id").value(changed.change().asgn_chg_id()));
        assertThat(financialSnapshot()).isEqualTo(before);
        var rows=service.list(assignment,null).items();
        assertThat(rows).filteredOn(r -> r.asgn_chg_ty_cd().equals("FIX")).singleElement().satisfies(r -> {
            assertThat(r.fix_trgt_aply_dt()).isEqualTo(changed.change().aply_dt());
            assertThat(r.fix_trgt_chg_ty_cd()).isEqualTo("EXTEND");
            assertThat(r.bfr_val()).isEqualTo(changed.change().aft_val());
        });
        assertThat(rows).filteredOn(r -> r.asgn_chg_id()==changed.change().asgn_chg_id()).singleElement().satisfies(r -> {
            assertThat(r.bfr_val()).isEqualTo(changed.change().bfr_val()); assertThat(r.aft_val()).isEqualTo(changed.change().aft_val());
        });
    }
    @Test void correctionFailureRollsBackTargetMarkerAndFix() {
        var changed=save(input("EXTEND"));
        jdbc.execute("ALTER TABLE asgn.asgn_chg_hist ADD CONSTRAINT reject_assignment_history CHECK(false) NOT VALID");
        String before=snapshot(); assertThatThrownBy(()->service.fix(assignment,changed.change().asgn_chg_id(),correction())).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void correctionOfAnotherAssignmentHistoryIsNotFound() {
        conflict(()->service.fix(assignment,Long.MAX_VALUE,correction()),"NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"START","FIX"})
    void forbiddenCorrectionKindsNeverAppend(String type) {
        long key=append(type); conflict(()->service.fix(assignment,key,correction()),"NOT_FIXABLE");
    }
    @Test void twiceCorrectingTargetIsNotFixable() {
        long key=append("EXTEND"); service.fix(assignment,key,correction());
        conflict(()->service.fix(assignment,key,correction()),"NOT_FIXABLE");
    }
    @ParameterizedTest @CsvSource({"2026-10-13,Y","2026-10-14,N"})
    void retroReasonAndSeoulFlagUseFixedClock(String date,String retro) {
        var n=input("EXT_CHANCE"); n.put("aply_dt",date); n.put("rsn_cd","CUST");
        assertThat(save(n).change().retro_yn()).isEqualTo(retro);
    }
    @Test void missingRetroReasonRollsBackTouch() {
        var n=input("EXT_CHANCE"); n.put("aply_dt","2026-10-13"); conflict(()->save(n),"INVALID_INPUT");
    }
    @Test void closedRateFinancialEffectStartsOctoberWhileSeptemberAndHistoryDateArePreserved() {
        String september=jdbc.queryForObject("SELECT row_to_json(m)::text FROM asgn.asgn_mth m WHERE m.asgn_id=? AND m.work_ym='2026-09'",String.class,assignment);
        var n=input("RATE"); n.put("work_ym","2026-09"); n.put("aply_dt","2026-09-01"); n.put("rsn_cd","PRICE");
        var r=save(n);
        assertThat(r.change().aply_dt()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(r.assignment().rates()).containsExactly(new RateRow(LocalDate.parse("2026-09-01"),7000000L),new RateRow(LocalDate.parse("2026-10-01"),7200000L));
        assertThat(jdbc.queryForObject("SELECT row_to_json(m)::text FROM asgn.asgn_mth m WHERE m.asgn_id=? AND m.work_ym='2026-09'",String.class,assignment)).isEqualTo(september);
    }
    @Test void september20ChangePreservesClosedMmAndSignalsClosedBoundary() {
        var n=input("EXT_CHANCE"); n.put("aply_dt","2026-09-20"); n.put("rsn_cd","CUST");
        var r=save(n); assertThat(r.assignment().warnings()).anyMatch(w -> w.code().equals("CLOSED_MONTH"));
        assertThat(jdbc.queryForObject("SELECT m.mm_qty FROM asgn.asgn_mth m WHERE m.asgn_id=? AND m.work_ym='2026-09'",String.class,assignment)).isEqualTo("1.00");
    }
    @ParameterizedTest @CsvSource({"2026-09-30,true","2026-10-01,false"})
    void closedWarningHasExactMonthBoundary(String date,boolean warning) {
        var n=input("EXT_CHANCE"); n.put("aply_dt",date); n.put("rsn_cd","CUST");
        assertThat(save(n).assignment().warnings().stream().anyMatch(w -> w.code().equals("CLOSED_MONTH"))).isEqualTo(warning);
    }
    @Test void endedBeforeFirstOpenFinancialDateOnlyAppendsHistory() {
        jdbc.update("UPDATE asgn.asgn SET end_dt='2026-09-30' WHERE asgn_id=?",assignment);
        String rates=jdbc.queryForObject("SELECT json_agg(r ORDER BY r.aply_bgn_dt)::text FROM asgn.untprc_hist r WHERE r.asgn_id=?",String.class,assignment);
        var n=input("RATE"); n.put("work_ym","2026-09"); n.put("aply_dt","2026-09-01"); n.put("rsn_cd","PRICE"); save(n);
        assertThat(jdbc.queryForObject("SELECT json_agg(r ORDER BY r.aply_bgn_dt)::text FROM asgn.untprc_hist r WHERE r.asgn_id=?",String.class,assignment)).isEqualTo(rates);
    }
    @ParameterizedTest @ValueSource(strings={"2026-09-01","2026-10-01"})
    void closedOldOrNewStartPreventsFirstRateDateMove(String original) {
        jdbc.update("UPDATE asgn.asgn SET bgn_dt=?::date WHERE asgn_id=?",original,assignment);
        var n=input("PERIOD"); n.put("bgn_dt",original.equals("2026-09-01")?"2026-10-01":"2026-09-01");
        conflict(()->save(n),"CLOSED_MONTH");
    }
    @Test void openPeriodMovesFirstRateAndClearsContractPersonOnSubExit() {
        jdbc.update("UPDATE asgn.asgn SET bgn_dt='2026-10-01',asgn_se_cd='SUB',cntrct_dev_id=? WHERE asgn_id=?",developer,assignment);
        jdbc.update("UPDATE asgn.untprc_hist SET aply_bgn_dt='2026-10-01' WHERE asgn_id=?",assignment);
        var n=input("PERIOD"); n.put("bgn_dt","2026-10-02"); var r=save(n);
        assertThat(r.assignment().header().bgn_dt()).isEqualTo(LocalDate.parse("2026-10-02"));
        assertThat(r.assignment().rates().get(0).aply_bgn_dt()).isEqualTo(LocalDate.parse("2026-10-02"));
        assertThat(r.assignment().header().cntrct_dev_id()).isNull();
    }
    @ParameterizedTest @CsvSource({"2026-11-19,false","2026-11-20,true"})
    void dispatchTwoYearBoundaryIsWarningNotBlock(String end,boolean warning) {
        jdbc.update("UPDATE prj.prj SET cntrct_ty_cd='DP' WHERE prj_id=?",project);
        jdbc.update("UPDATE asgn.asgn SET bgn_dt='2024-11-20',end_dt='2026-10-31' WHERE asgn_id=?",assignment);
        var n=input("EXTEND"); n.put("end_dt",end); var r=save(n);
        assertThat(r.assignment().warnings().stream().anyMatch(w -> w.code().equals("DISPATCH_MAX_MONTHS"))).isEqualTo(warning);
        assertThat(r.assignment().header().end_dt()).isEqualTo(LocalDate.parse(end));
    }
    @Test void extensionAfterResignationSavesWithWarning() {
        jdbc.update("UPDATE mst.dev SET rsgn_dt='2026-12-31' WHERE dev_id=?",developer);
        assertThat(save(input("EXTEND")).assignment().warnings()).anyMatch(w -> w.code().equals("AFTER_RESIGNATION"));
    }
    @Test void overMmExactlyOneHasNoWarningAndRemovedCauseIsReevaluated() {
        var first=save(input("EXT_CHANCE")); assertThat(first.assignment().warnings()).noneMatch(w -> w.code().equals("OVER_MM"));
        jdbc.update("UPDATE asgn.asgn_mth SET mm_qty=1.01 WHERE asgn_id=? AND work_ym='2026-10'",assignment);
        assertThat(mapper.warnings(assignment)).anyMatch(w -> w.code().equals("OVER_MM"));
        jdbc.update("UPDATE asgn.asgn_mth SET mm_qty=1.00 WHERE asgn_id=? AND work_ym='2026-10'",assignment);
        assertThat(mapper.warnings(assignment)).noneMatch(w -> w.code().equals("OVER_MM"));
    }
}
