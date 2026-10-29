package com.sicost.parts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.costallocation.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.parts.PartSettlementModels.*;

class PartSettlementPersistenceTest {
    @Configuration("partSettlementTestTransactions") @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PartSettlementMapper mapper;
    PartSettlementService service;
    Scope scope = new Scope(901,"2026-10");
    Clock clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null,"Test database connection settings are absent");
        database = "sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try (Connection admin = DriverManager.getConnection(adminUrl,user,password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement,database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/parts/PartSettlementMapper.xml"),
            new ClassPathResource("mappers/costallocation/CostAllocationMapper.xml")); factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(PartSettlementMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class,PartSettlementService.class,CostAllocationService.class,CurrentUser.class);
        context.registerBean(PartSettlementMapper.class,()->mapper);
        context.registerBean(CostAllocationMapper.class,()->session.getMapper(CostAllocationMapper.class));
        context.registerBean(PartSettlementApprovalPolicy.class,()->new PartSettlementApprovalPolicy() {
            @Override public boolean approved() { return true; }
        });
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
        context.registerBean(Clock.class,()->clock); context.refresh(); service = context.getBean(PartSettlementService.class);
        sql("INSERT INTO mst.bsn_part(bsn_part_id,bsn_part_nm) VALUES(901,'정산 검증 파트'),(902,'다른 정산 파트')");
        sql("INSERT INTO mst.bsn_part_std_hist(part_std_hist_id,bsn_part_id,aply_bgn_ym,ldr_nm,prft_dstrb_rt,bsn_fee_amt) VALUES(901,901,'2026-01','파트장',40,10)");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,bsn_part_id) VALUES(901,'K-00901','정산 검증','FT',902)");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(901,'P-26901','정산 원천','MO','RUN')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(901,'A-00901',901,901,'BE','2026-10-16','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(901,'2026-10',0.50)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(901,'2026-10-16',4000)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,bsn_part_id,slry_amt,rtrpay_acml_amt) VALUES(901,'2026-10','FT',901,3100,100)");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl,user,password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    void sql(String sql) { new JdbcTemplate(source).execute(sql); }
    String value(String sql) { return new JdbcTemplate(source).queryForObject(sql,String.class); }
    String snapshot() {
        return value("SELECT json_build_object('header',(SELECT json_agg(s ORDER BY stlm_id) FROM fin.part_stlm s),"
            + "'detail',(SELECT json_agg(d ORDER BY stlm_dtl_id) FROM fin.part_stlm_dtl d),"
            + "'history',(SELECT json_agg(h ORDER BY stlm_hist_id) FROM fin.part_stlm_hist h),"
            + "'audit',(SELECT json_agg(a ORDER BY data_chg_hist_id) FROM sys.data_chg_hist a))::text");
    }
    @Test void naturalKeyCreationSnapshotRemarkAndCancellationSurviveIndependentSessions() {
        service.card(scope,new Card(5,"카드 비고"));
        var result = service.confirm(scope);
        assertThat(result.settlement().part_prft_amt()).isEqualTo(111);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).slry_amt()).isEqualTo(1600);
        assertThat(mapper.settlement(scope).rmrk_desc()).isEqualTo("카드 비고");
        sql("UPDATE cst.mth_cst SET slry_amt=9999 WHERE dev_id=901");
        assertThat(service.detail(scope,"MONTH").items().get(0).slry_amt()).isEqualTo(1600);
        service.cancel(scope,"재검토");
        assertThat(value("SELECT stlm_stts_cd FROM fin.part_stlm WHERE bsn_part_id=901")).isEqualTo("DRAFT");
        assertThat(value("SELECT count(*) FROM fin.part_stlm_hist")).isEqualTo("2");
        assertThat(mapper.details(result.settlement().stlm_id()).get(0).slry_amt()).isEqualTo(1600);
        assertThat(mapper.settlement(scope).rmrk_desc()).isEqualTo("카드 비고");
        assertThat(value("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='SETTLE_CONFIRM' AND user_id IS NULL")).isEqualTo("1");
    }
    @Test void payrollMonthPartWinsOverCurrentDeveloperPartAndHeadquartersIsExcluded() {
        assertThat(service.detail(scope,"MONTH").items()).hasSize(1);
        assertThat(service.detail(new Scope(902,"2026-10"),"MONTH").items()).isEmpty();
        assertThat(mapper.parts()).noneMatch(p -> p.bsn_part_nm().equals("본사"));
        assertThat(mapper.people(scope).get(0).first_start()).isEqualTo(LocalDate.of(2026,10,16));
    }
    @ParameterizedTest @ValueSource(strings={"card","confirm","cancel"})
    void auditFailureRollsBackHeaderDetailsAndHistory(String operation) {
        service.card(scope,new Card(1,"원본"));
        if (operation.equals("cancel")) service.confirm(scope);
        String before = snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_part_history CHECK(false) NOT VALID");
        assertThatThrownBy(()-> {
            switch (operation) {
                case "card" -> service.card(scope,new Card(2,"변경"));
                case "confirm" -> service.confirm(scope);
                default -> service.cancel(scope,"취소");
            }
        }).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void concurrentConfirmationHasExactlyOneWinner() throws Exception {
        service.card(scope,new Card(0,null));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> confirm = () -> {
            start.await();
            try { service.confirm(scope); return "CONFIRM"; }
            catch (ApiException ex) { return ex.code(); }
        };
        try {
            var first = pool.submit(confirm); var second = pool.submit(confirm); start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("CONFIRM","STALE_SETTLEMENT");
            assertThat(value("SELECT count(*) FROM fin.part_stlm_hist WHERE stlm_prcs_cd='CONFIRM'")).isEqualTo("1");
        } finally { pool.shutdownNow(); }
    }
    @Test void closedSnapshotIsImmutableAndMissingAllocationIsNotRecalculated() {
        service.confirm(scope);
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        assertThat(service.detail(scope,"MONTH").items()).hasSize(1);
        assertThatThrownBy(()->service.cancel(scope,"취소")).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(value("SELECT count(*) FROM cst.cst_aloc")).isEqualTo("0");
    }
    @Test void trendHasFiveMetricsPerPersonAndUsesConfirmedSnapshot() {
        service.confirm(scope);
        var trend = service.detail(scope,"TREND").trend();
        assertThat(trend).hasSize(5);
        assertThat(trend.get(0).months()).hasSize(10);
        assertThat(trend.get(0).months().get(9).confirmed()).isTrue();
    }
}
