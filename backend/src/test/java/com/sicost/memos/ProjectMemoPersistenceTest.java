package com.sicost.memos;

import static com.sicost.memos.ProjectMemoModels.*;
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
import org.junit.jupiter.params.provider.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.http.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectMemoPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ProjectMemoService service;
    ProjectMemoMapper mapper;
    com.sicost.projectsops.ProjectProgressMapper progress;
    com.sicost.apiops.ProjectBoardMapper board;
    MockMvc mvc;
    ObjectMapper json;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl,user,password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/memos/ProjectMemoMapper.xml"),
            new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"),
            new ClassPathResource("mappers/projectsops/ProjectProgressMapper.xml"));
        factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(ProjectMemoMapper.class);
        progress = session.getMapper(com.sicost.projectsops.ProjectProgressMapper.class);
        board = session.getMapper(com.sicost.apiops.ProjectBoardMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class,ProjectMemoService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(ProjectMemoMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("UTC")));
        context.refresh(); service = context.getBean(ProjectMemoService.class);
        json = new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new ProjectMemoController(service,json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,prfm_bgn_dt) VALUES(1,'P-2601','메모 시험','TK','RUN','2026-01-01')");
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl,user,password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception { try (var c=source.getConnection(); var s=c.createStatement()) { s.execute(text); } }
    String value(String text) throws Exception {
        try (var c=source.getConnection(); var s=c.createStatement(); var r=s.executeQuery(text)) { r.next(); return r.getString(1); }
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('memos',(SELECT json_agg(m ORDER BY memo_id) FROM prj.wkly_memo m),'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    Map<String,Object> body(int rate,String completed) { return new LinkedHashMap<>(Map.of("actl_prgs_rt",rate,"cmpl_work_desc",completed,"plan_work_desc","다음 시험")); }
    MemoInput input(int rate,String text) { return ProjectMemoInput.parse(json.valueToTree(body(rate,text))); }
    ResultActions send(String method,String path,Map<String,Object> body) throws Exception {
        return mvc.perform(request(HttpMethod.valueOf(method),path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    @Test void anonymousTodayUpsertCommitsOneRowAndReadSourcesHaveZeroElapsedNoGenericHistory() throws Exception {
        send("POST","/api/projects/1/memos",body(42,"첫 한 일")).andExpect(status().isOk()).andExpect(jsonPath("$.data.actl_prgs_rt").value(42));
        assertThat(value("SELECT count(*) FROM prj.wkly_memo WHERE prj_id=1 AND wrt_dt='2026-10-23' AND wrt_user_id IS NULL AND actl_prgs_rt=42")).isEqualTo("1");
        String key = value("SELECT memo_id FROM prj.wkly_memo WHERE prj_id=1");
        send("POST","/api/projects/1/memos",body(60,"두번째 내용")).andExpect(status().isOk());
        assertThat(value("SELECT count(*) FROM prj.wkly_memo WHERE prj_id=1")).isEqualTo("1");
        assertThat(value("SELECT memo_id FROM prj.wkly_memo WHERE prj_id=1")).isEqualTo(key);
        assertThat(value("SELECT cmpl_work_desc FROM prj.wkly_memo WHERE prj_id=1")).isEqualTo("두번째 내용");
        assertThat(value("SELECT DATE '2026-10-23' - max(wrt_dt) FROM prj.wkly_memo WHERE prj_id=1")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM prj.prj p WHERE p.prj_id=1 AND DATE '2026-10-23' - (SELECT max(wrt_dt) FROM prj.wkly_memo m WHERE m.prj_id=p.prj_id)>14")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.wkly_memo'")).isEqualTo("0");
        var read = progress.source(new com.sicost.projectsops.ProjectProgressModels.ProgressRead(1,LocalDate.of(2026,10,23)));
        assertThat(read.last_memo_dt()).isEqualTo(LocalDate.of(2026,10,23));
        assertThat(read.actl_prgs_rt()).isEqualByComparingTo("60");
        var boardSource = board.projects(LocalDate.of(2026,10,23)).get(0);
        var calculated = com.sicost.apiops.ProjectProgressCalculator.calculate(boardSource,board.phases(1),null,
            board.params("2026-10"),LocalDate.of(2026,10,23));
        assertThat(calculated.memo_elapsed_days()).isZero();
        assertThat(calculated.last_memo_dt()).isEqualTo(read.last_memo_dt());
        assertThat(calculated.reasons()).noneMatch(reason -> reason.contains("상태 업데이트 없음"));
    }
    @Test void latestEditKeepsDateIdentityAndChangesReadSource() throws Exception {
        sql("INSERT INTO prj.wkly_memo(prj_id,wrt_dt,actl_prgs_rt,cmpl_work_desc,plan_work_desc) VALUES(1,'2026-10-15',30,'전 내용','전 계획')");
        long key = Long.parseLong(value("SELECT memo_id FROM prj.wkly_memo WHERE prj_id=1"));
        send("PUT","/api/projects/1/memos/"+key,body(80,"바꾼 내용")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.wrt_dt").value("2026-10-15")).andExpect(jsonPath("$.data.memo_id").value(key));
        var latest = mapper.latest(1);
        assertThat(latest.cmpl_work_desc()).isEqualTo("바꾼 내용"); assertThat(latest.plan_work_desc()).isEqualTo("다음 시험");
        assertThat(latest.actl_prgs_rt()).isEqualByComparingTo("80");
        assertThat(value("SELECT count(*) FROM prj.wkly_memo WHERE prj_id=1")).isEqualTo("1");
    }
    @Test void putSideEffectUsesSameMemoIdAndProgressRecalculatesFromPreservedWrtDt() throws Exception {
        sql("INSERT INTO prj.wkly_memo(memo_id,prj_id,wrt_dt,actl_prgs_rt,cmpl_work_desc,plan_work_desc) VALUES(7,1,'2026-10-15',30,'전 내용','전 계획')");
        mvc.perform(put("/api/projects/1/memos/7").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body(80,"수정 내용"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.memo_id").value(7))
            .andExpect(jsonPath("$.data.wrt_dt").value("2026-10-15"));
        var read=progress.source(new com.sicost.projectsops.ProjectProgressModels.ProgressRead(1,LocalDate.of(2026,10,23)));
        assertThat(progress.memos(1)).singleElement().satisfies(m -> {
            assertThat(m.memo_id()).isEqualTo(7);assertThat(m.cmpl_work_desc()).isEqualTo("수정 내용");
        });
        var calculated=com.sicost.apiops.ProjectProgressCalculator.calculate(read,board.phases(1),null,
            board.params("2026-10"),LocalDate.of(2026,10,23));
        assertThat(calculated.actl_prgs_rt()).isEqualByComparingTo("80");
        assertThat(calculated.last_memo_dt()).isEqualTo(LocalDate.of(2026,10,15));
        assertThat(value("SELECT count(*) FROM prj.wkly_memo WHERE prj_id=1")).isEqualTo("1");
    }
    @Test void oldMemoConflictPreservesAllRowsAndDates() throws Exception {
        sql("INSERT INTO prj.wkly_memo(prj_id,wrt_dt,actl_prgs_rt,cmpl_work_desc,plan_work_desc) VALUES(1,'2026-10-01',10,'과거','과거 계획')");
        String key = value("SELECT memo_id FROM prj.wkly_memo WHERE prj_id=1"); service.save(1,input(42,"오늘")); String before = snapshot();
        send("PUT","/api/projects/1/memos/"+key,body(50,"수정")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_LATEST_MEMO"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @CsvSource({"POST,actl_prgs_rt,105","POST,actl_prgs_rt,42.5","POST,cmpl_work_desc,blank","PUT,plan_work_desc,blank","PUT,actl_prgs_rt,105","PUT,actl_prgs_rt,42.5"})
    void invalidWritesLeavePersistedRowAndHistoryUnchanged(String method,String field,String value) throws Exception {
        var row = service.save(1,input(42,"보존")); String before = snapshot(); var body = body(42,"변경");
        body.put(field,value.equals("blank")?" ":new java.math.BigDecimal(value));
        send(method,"/api/projects/1/memos"+(method.equals("PUT")?"/"+row.memo_id():""),body)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(ints={0,100})
    void integerBoundariesPersistExactly(int rate) {
        var saved = service.save(1,input(rate,"한 일")); assertThat(saved.actl_prgs_rt()).isEqualTo(rate);
        assertThat(mapper.latest(1).actl_prgs_rt()).isEqualByComparingTo(Integer.toString(rate));
    }
    @ParameterizedTest @ValueSource(strings={"MO","DP"})
    void operatingRateIsNullOnBothWrites(String type) throws Exception {
        sql("UPDATE prj.prj SET cntrct_ty_cd='"+type+"' WHERE prj_id=1");
        var row = service.save(1,input(42,"운영")); assertThat(row.actl_prgs_rt()).isNull();
        assertThat(service.update(1,row.memo_id(),input(60,"운영 수정")).actl_prgs_rt()).isNull();
        assertThat(value("SELECT actl_prgs_rt FROM prj.wkly_memo WHERE prj_id=1")).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void endProjectHasNoMutation(String method) throws Exception {
        var row = service.save(1,input(42,"보존")); sql("UPDATE prj.prj SET prj_stts_cd='END' WHERE prj_id=1"); String before = snapshot();
        send(method,"/api/projects/1/memos"+(method.equals("PUT")?"/"+row.memo_id():""),body(50,"변경"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED")); assertThat(snapshot()).isEqualTo(before);
    }
    @Test void concurrentTodayWritesCommitOneIdentity() throws Exception {
        var executor = Executors.newFixedThreadPool(3); var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); return service.save(1,input(42,"첫번째")); });
            var b = executor.submit(() -> { start.await(); return service.save(1,input(60,"두번째")); });
            var c = executor.submit(() -> { start.await(); return service.save(1,input(70,"세번째")); });
            start.countDown(); var one = a.get(10,TimeUnit.SECONDS); var two = b.get(10,TimeUnit.SECONDS); var three = c.get(10,TimeUnit.SECONDS);
            assertThat(one.memo_id()).isEqualTo(two.memo_id()).isEqualTo(three.memo_id());
            assertThat(value("SELECT count(*) FROM prj.wkly_memo WHERE prj_id=1 AND wrt_dt='2026-10-23'")).isEqualTo("1");
        } finally { executor.shutdownNow(); }
    }
    @Test void oldEditWaitsForTodaySaveThenRejectsStaleLatest() throws Exception {
        sql("INSERT INTO prj.wkly_memo(prj_id,wrt_dt,actl_prgs_rt,cmpl_work_desc,plan_work_desc) VALUES(1,'2026-10-01',10,'보존','보존 계획')");
        long old = mapper.latest(1).memo_id();
        var executor = Executors.newSingleThreadExecutor();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT prj_id FROM prj.prj WHERE prj_id=1 FOR UPDATE");
                var started = new CountDownLatch(1);
                var waiting = executor.submit(() -> { started.countDown(); return service.update(1,old,input(90,"금지")); });
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
                statement.execute("INSERT INTO prj.wkly_memo(prj_id,wrt_dt,actl_prgs_rt,cmpl_work_desc,plan_work_desc) VALUES(1,'2026-10-23',42,'오늘','오늘 계획')");
                connection.commit();
                assertThatThrownBy(() -> waiting.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
                assertThat(mapper.row(new MemoKey(1,old)).cmpl_work_desc()).isEqualTo("보존");
            }
        } finally { executor.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void databaseFailureRollsBackWholeWrite(String method) throws Exception {
        var row = service.save(1,input(42,"보존")); String before = snapshot();
        sql("ALTER TABLE prj.wkly_memo ADD CONSTRAINT memo_reject CHECK(cmpl_work_desc <> '실패') NOT VALID");
        assertThatThrownBy(() -> { if (method.equals("POST")) service.save(1,input(60,"실패")); else service.update(1,row.memo_id(),input(60,"실패")); }).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
}
