package com.sicost.receipts;

import static com.sicost.receipts.BillingReceiptModels.*;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BillingReceiptPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingReceiptService service;
    BillingReceiptMapper mapper;
    MockMvc mvc;
    ObjectMapper json;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/receipts/BillingReceiptMapper.xml"));
        factory.afterPropertiesSet(); mapper = new SqlSessionTemplate(factory.getObject()).getMapper(BillingReceiptMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, BillingReceiptService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(BillingReceiptMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
            () -> new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        context.refresh(); service = context.getBean(BillingReceiptService.class);
        json = context.getBean(ObjectMapper.class);
        mvc = MockMvcBuilders.standaloneSetup(new BillingReceiptController(service, json))
            .setControllerAdvice(new BillingReceiptErrors(), new ApiExceptionHandler())
            .addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd) VALUES(1,'R-TEST','입금 검증','MO')");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd) "
            + "VALUES(1,'R-ROOT',1,'2026-01','2026-02-01',100,10,'CONFIRM')");
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    void sql(String statement) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(statement); }
    }
    String value(String statement) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(statement)) {
            r.next(); return r.getString(1);
        }
    }
    ReceiptInput input(long amount) { return new ReceiptInput(LocalDate.of(2026, 10, 23), amount, "분할", true); }
    long create(long amount) { return service.createBillingReceipt(1, input(amount)).receipts().get(0).receipt_id(); }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('b',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b),"
            + "'r',(SELECT json_agg(r ORDER BY rcpt_id) FROM bill.rcpt r),"
            + "'h',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    void closed(String month) throws Exception {
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('" + month + "','CLOSED') "
            + "ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
    }
    @Test void anonymousCrudMaterializesAdjustedReceivableAndKeepsOriginalSnapshot() throws Exception {
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,sply_amt,vat_amt,bill_stts_cd,amnd_yn,orgnl_bill_id) "
            + "VALUES(2,'R-AMEND',1,-10,-1,'CONFIRM','Y',1),(3,'R-DRAFT',1,-10,-1,'DRAFT','Y',1)");
        closed("2026-01");
        String original = value("SELECT row_to_json(b)::text FROM bill.bill b WHERE bill_id=1");
        var response = mvc.perform(post("/api/billings/1/receipts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"rcpt_dt\":\"2026-10-23\",\"rcpt_amt\":30,\"rmrk_desc\":\"첫 입금\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.adjusted_total_amt").value(99))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(69)).andReturn();
        long id = json.readTree(response.getResponse().getContentAsByteArray()).path("data").path("receipts").get(0).path("receipt_id").asLong();
        mvc.perform(put("/api/billings/1/receipts/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"rcpt_dt\":\"2026-11-01\",\"rcpt_amt\":40,\"rmrk_desc\":\"수정\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.receivable_amt").value(59));
        assertThat(value("SELECT bfr_val FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).contains("첫 입금", "30", "2026-10-23");
        assertThat(value("SELECT aft_val FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).contains("수정", "40", "2026-11-01");
        mvc.perform(delete("/api/billings/1/receipts/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(99));
        mvc.perform(get("/api/billings/1/receipts")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.receipts").isEmpty()).andExpect(jsonPath("$.data.change_history.length()").value(3));
        assertThat(value("SELECT row_to_json(b)::text FROM bill.bill b WHERE bill_id=1")).isEqualTo(original);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.rcpt' AND chg_user_id IS NULL")).isEqualTo("3");
    }
    @ParameterizedTest @ValueSource(strings = {"CREATE", "UPDATE", "DELETE"})
    void auditFailureRollsBackBusinessRows(String operation) throws Exception {
        long id = operation.equals("CREATE") ? 0 : create(30);
        String before = snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(() -> {
            switch (operation) {
                case "CREATE" -> service.createBillingReceipt(1, input(30));
                case "UPDATE" -> service.updateBillingReceipt(1, id, input(40));
                default -> service.deleteBillingReceipt(1, id);
            }
        }).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings = {"CREATE", "UPDATE", "DELETE"})
    void closedReceiptMonthPreservesAllRowsAndAudit(String operation) throws Exception {
        long id = operation.equals("CREATE") ? 0 : create(30); closed("2026-10"); String before = snapshot();
        assertThatThrownBy(() -> {
            switch (operation) {
                case "CREATE" -> service.createBillingReceipt(1, input(30));
                case "UPDATE" -> service.updateBillingReceipt(1, id,
                    new ReceiptInput(LocalDate.of(2026, 11, 1), 40L, null, false));
                default -> service.deleteBillingReceipt(1, id);
            }
        }).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void newClosedMonthRejectsDateChange() throws Exception {
        long id = create(30); closed("2026-11"); String before = snapshot();
        assertThatThrownBy(() -> service.updateBillingReceipt(1, id,
            new ReceiptInput(LocalDate.of(2026, 11, 1), 30L, null, false)))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void receiptOfAnotherBillCannotBeUpdatedOrDeleted() throws Exception {
        long id = create(30);
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,sply_amt,vat_amt,bill_stts_cd) VALUES(2,'R-OTHER',1,100,10,'CONFIRM')");
        String before = snapshot();
        assertThatThrownBy(() -> service.updateBillingReceipt(2, id, input(40))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.deleteBillingReceipt(2, id)).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(longs = {30, 70})
    void concurrentSplitReceiptsSerializeAndOnlyExcessFails(long amount) throws Exception {
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            Callable<String> command = () -> {
                start.await();
                try { service.createBillingReceipt(1, input(amount)); return "OK"; }
                catch (ApiException ex) { return ex.code(); }
            };
            var a = executor.submit(command); var b = executor.submit(command); start.countDown();
            var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            if (amount == 30) {
                assertThat(results).containsExactly("OK", "OK");
                assertThat(value("SELECT sum(rcpt_amt) FROM bill.rcpt")).isEqualTo("60");
                assertThat(value("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo("2");
            } else {
                assertThat(results).containsExactlyInAnyOrder("OK", "OVER_RECEIPT");
                assertThat(value("SELECT sum(rcpt_amt) FROM bill.rcpt")).isEqualTo("70");
                assertThat(value("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo("1");
            }
        } finally { executor.shutdownNow(); }
    }
}
