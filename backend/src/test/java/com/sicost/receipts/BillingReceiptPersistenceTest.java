package com.sicost.receipts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
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
import static com.sicost.receipts.BillingReceiptModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BillingReceiptPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingReceiptService service;
    BillingReceiptMapper mapper;
    org.springframework.test.web.servlet.MockMvc mvc;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/receipts/BillingReceiptMapper.xml")); factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(BillingReceiptMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, BillingReceiptService.class, BillingReceivableService.class,
            CurrentUser.class, PermissionService.class);
        context.registerBean(BillingReceiptMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
            () -> new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        context.refresh(); service = context.getBean(BillingReceiptService.class);
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BillingReceiptController(service, json))
            .setControllerAdvice(new com.sicost.common.api.ApiExceptionHandler())
            .addFilters(new com.sicost.common.api.RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd) VALUES(101,'P-2691','입금 프로젝트','MO')");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd) VALUES "
            + "(101,'B-2610-91',101,'2026-09','2026-09-01',100,10,'CONFIRM'),"
            + "(102,'B-2610-92',101,'2026-10','2026-10-01',50,5,'CONFIRM')");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }
    void sql(String sql) { new JdbcTemplate(source).execute(sql); }
    String value(String sql) { return new JdbcTemplate(source).queryForObject(sql, String.class); }
    ReceiptInput input(String date, long amount) { return new ReceiptInput(null, LocalDate.parse(date), amount, "분할 입금"); }
    String snapshot() {
        return value("SELECT json_build_object('receipts',(SELECT json_agg(r ORDER BY rcpt_id) FROM bill.rcpt r),"
            + "'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),"
            + "'bills',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b))::text");
    }
    String original() { return value("SELECT row_to_json(b)::text FROM bill.bill b WHERE bill_id=101"); }
    long receiptId() { return service.list(101).receipts().get(0).receipt_id(); }

    @Test void anonymousCrudMaterializesAllProjectionsAndSurvivesNewServiceContextRead() throws Exception {
        String original = original();
        mvc.perform(post("/api/billings/101/receipts").contentType("application/json")
            .content("{\"rcpt_dt\":\"2026-10-10\",\"rcpt_amt\":30}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.receivable_amt").value(80));
        long id = receiptId();
        mvc.perform(put("/api/billings/101/receipts/{id}", id).contentType("application/json")
            .content("{\"rcpt_dt\":\"2026-11-01\",\"rcpt_amt\":40,\"rmrk_desc\":\"변경\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.receivable_amt").value(70));
        var freshFactory = new SqlSessionFactoryBean(); freshFactory.setDataSource(source);
        freshFactory.setMapperLocations(new ClassPathResource("mappers/receipts/BillingReceiptMapper.xml")); freshFactory.afterPropertiesSet();
        var fresh = new SqlSessionTemplate(freshFactory.getObject()).getMapper(BillingReceiptMapper.class);
        assertThat(fresh.list(101).get(0).rcpt_dt()).hasToString("2026-11-01");
        assertThat(fresh.history(101)).hasSize(2);
        mvc.perform(delete("/api/billings/101/receipts/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(110))
            .andExpect(jsonPath("$.data.receipts").isEmpty());
        assertThat(value("SELECT bfr_val::jsonb->>'rmrk_desc' FROM sys.data_chg_hist WHERE data_chg_ty_cd='DELETE'"))
            .isEqualTo("변경");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE chg_user_id IS NULL")).isEqualTo("3");
        assertThat(original()).isEqualTo(original);
    }
    @Test void confirmedAdjustmentsOnlyReduceLimitAndNeverChangeSupplyOrAttribution() {
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,sply_amt,vat_amt,bill_stts_cd,amnd_yn,orgnl_bill_id) VALUES "
            + "(103,'B-2610-93',101,'2026-09',-20,-2,'CONFIRM','Y',101),"
            + "(104,'B-2610-94',101,'2026-09',-10,-1,'DRAFT','Y',101),"
            + "(105,'B-2610-95',101,'2026-09',-10,-1,'CANCEL','Y',101)");
        String original = original(); var result = service.create(101, input("2026-10-01", 30));
        assertThat(result.billing().adjusted_total_amt()).isEqualTo(88);
        assertThat(result.billing().receivable_amt()).isEqualTo(58);
        assertThat(result.amendments()).hasSize(3);
        String before = snapshot();
        assertThatThrownBy(() -> service.create(101, input("2026-10-01", 59)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("OVER_RECEIPT"));
        assertThat(snapshot()).isEqualTo(before); assertThat(original()).isEqualTo(original);
    }
    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void closedMonthRejectsWithoutChangingCashBalanceOrAudit(String method) {
        sql("INSERT INTO bill.rcpt(rcpt_id,bill_id,rcpt_dt,rcpt_amt) VALUES(201,101,'2026-09-01',20)");
        String before = snapshot();
        assertThatThrownBy(() -> {
            switch (method) {
                case "POST" -> service.create(101, input("2026-09-20", 30));
                case "PUT" -> service.update(101, 201, input("2026-10-20", 30));
                default -> service.delete(101, 201);
            }
        }).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void auditFailureRollsBackMutationAndBalance(String method) {
        service.create(101, input("2026-10-01", 20)); long id = receiptId(); String before = snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_receipt_history CHECK(false) NOT VALID");
        assertThatThrownBy(() -> {
            switch (method) {
                case "POST" -> service.create(101, input("2026-10-20", 30));
                case "PUT" -> service.update(101, id, input("2026-11-20", 30));
                default -> service.delete(101, id);
            }
        }).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void targetClosedMonthAndForeignReceiptNeverMutate() {
        service.create(102, input("2026-10-01", 20)); long foreign = service.list(102).receipts().get(0).receipt_id();
        String before = snapshot();
        assertThatThrownBy(() -> service.delete(101, foreign)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.update(101, foreign, input("2026-10-01", 20))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
        service.create(101, input("2026-10-01", 20)); long id = receiptId(); before = snapshot();
        assertThatThrownBy(() -> service.update(101, id, input("2026-09-01", 20))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void competingReceiptsSerializeOnRootAcrossIndependentConnections() throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> attempt = () -> {
                ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                try { service.create(101, input("2026-10-10", 70)); return "OK"; }
                catch (ApiException ex) { return ex.code(); }
            };
            var a = pool.submit(attempt); var b = pool.submit(attempt);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("OK", "OVER_RECEIPT");
            assertThat(service.list(101).billing().received_amt()).isEqualTo(70);
            assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.rcpt'")).isEqualTo("1");
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
