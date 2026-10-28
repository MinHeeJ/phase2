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
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingReceiptService service;
    BillingReceiptMapper mapper;
    MockMvc mvc;
    ObjectMapper json;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/receipts/BillingReceiptMapper.xml"));
        factory.afterPropertiesSet();mapper=new SqlSessionTemplate(factory.getObject()).getMapper(BillingReceiptMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,BillingReceiptService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(BillingReceiptMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
        context.refresh();service=context.getBean(BillingReceiptService.class);
        json=context.getBean(ObjectMapper.class).copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc=MockMvcBuilders.standaloneSetup(new BillingReceiptController(service,json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,cntrct_bgn_dt,cntrct_end_dt) VALUES(1,'P-2601','입금 시험','MO','2026-01-01','2026-12-31')");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd) VALUES(1,'B-2610-01',1,'2026-09','2026-10-01',100,10,'CONFIRM'),(2,'B-2610-02',1,'2026-09','2026-10-01',100,10,'CONFIRM')");
        sql("INSERT INTO bill.bill_dtl(bill_id,work_ym,bill_amt) VALUES(1,'2026-09',100)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close();if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);}}
    String value(String text) throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}}
    String snapshot() throws Exception {
        return value("SELECT json_build_object('bill',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b),'details',(SELECT json_agg(d ORDER BY bill_dtl_id) FROM bill.bill_dtl d),'receipts',(SELECT json_agg(r ORDER BY rcpt_id) FROM bill.rcpt r),'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    ReceiptInput input(long amount) {return new ReceiptInput(LocalDate.of(2026,10,23),amount,"입금 시험");}
    long receipt() {return service.create(1,input(30)).receipts().get(0).receipt_id();}
    @Test void anonymousCrudReadsAdjustedBalanceAndPreservesRevenueSnapshot() throws Exception {
        String bills=value("SELECT json_agg(b ORDER BY bill_id)::text FROM bill.bill b");
        var created=mvc.perform(post("/api/billings/1/receipts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input(30))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.receivable_amt").value(80))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(100)).andExpect(jsonPath("$.data.details[0].bill_amt").value(100)).andReturn();
        long id=json.readTree(created.getResponse().getContentAsByteArray()).path("data").path("receipts").get(0).path("receipt_id").asLong();
        mvc.perform(put("/api/billings/1/receipts/{id}",id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ReceiptInput(LocalDate.of(2026,11,2),40,"변경")))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(70));
        assertThat(value("SELECT bfr_val FROM sys.data_chg_hist WHERE tbl_nm='bill.rcpt' AND data_chg_ty_cd='UPDATE'")).contains("30","2026-10-23","입금 시험");
        assertThat(value("SELECT aft_val FROM sys.data_chg_hist WHERE tbl_nm='bill.rcpt' AND data_chg_ty_cd='UPDATE'")).contains("40","2026-11-02","변경");
        mvc.perform(delete("/api/billings/1/receipts/{id}",id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.receivable_amt").value(110));
        assertThat(value("SELECT bfr_val FROM sys.data_chg_hist WHERE data_chg_ty_cd='DELETE'")).contains("40","변경");
        assertThat(value("SELECT json_agg(b ORDER BY bill_id)::text FROM bill.bill b")).isEqualTo(bills);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE chg_user_id IS NOT NULL")).isEqualTo("0");
        assertThat(service.list(1).change_history()).hasSize(3);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"})
    void auditFailureRollsBackReceiptAndBalance(String operation) throws Exception {
        long id=operation.equals("POST")?0:receipt();String before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->{switch(operation){case "POST"->service.create(1,input(20));case "PUT"->service.update(1,id,input(20));default->service.delete(1,id);}}).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"})
    void closedOldMonthRejectsWithoutAnySideEffect(String operation) throws Exception {
        long id=receipt();sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");String before=snapshot();
        assertThatThrownBy(()->{if(operation.equals("PUT"))service.update(1,id,new ReceiptInput(LocalDate.of(2026,11,2),20,null));else service.delete(1,id);})
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void pathOwnershipCannotDeleteOrEditOtherBillsReceipt() throws Exception {
        long id=receipt();String before=snapshot();
        assertThatThrownBy(()->service.delete(2,id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.update(2,id,input(20))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void confirmedAmendmentsReduceBalanceButDraftAndCancelledOnesDoNot() throws Exception {
        sql("INSERT INTO bill.bill(bill_no,prj_id,sply_amt,vat_amt,amnd_yn,orgnl_bill_id,bill_stts_cd) VALUES('A-1',1,-20,-2,'Y',1,'CONFIRM'),('A-2',1,-50,-5,'Y',1,'DRAFT'),('A-3',1,-50,-5,'Y',1,'CANCEL')");
        assertThat(service.create(1,input(30)).billing().path("receivable_amt").asLong()).isEqualTo(58);
        assertThat(service.list(1).amendments()).hasSize(3);
        String before=snapshot();assertThatThrownBy(()->service.create(1,input(59))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("OVER_RECEIPT"));assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(longs={30,70})
    void parentLockSerializesConcurrentReceiptsWithoutRejectingValidPairs(long amount) throws Exception {
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        Callable<String> write=()->{start.await();try{service.create(1,input(amount));return "OK";}catch(ApiException e){return e.code();}};
        try {
            var one=executor.submit(write);var two=executor.submit(write);start.countDown();
            var results=List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
            assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(amount==30?2:1);
            if(amount==70)assertThat(results).contains("OVER_RECEIPT");
            assertThat(value("SELECT sum(rcpt_amt) FROM bill.rcpt WHERE bill_id=1")).isEqualTo(amount==30?"60":"70");
            assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.rcpt'")).isEqualTo(amount==30?"2":"1");
        } finally {executor.shutdownNow();}
    }
}
