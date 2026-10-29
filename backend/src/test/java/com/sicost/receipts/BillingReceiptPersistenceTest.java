package com.sicost.receipts;

import static com.sicost.receipts.BillingReceiptModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.billing.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import com.sicost.shared.SharedReadMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class BillingReceiptPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingReceiptService service;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/receipts/BillingReceiptMapper.xml"),
            new ClassPathResource("mappers/common/BillingMutationMapper.xml"),
            new ClassPathResource("mappers/common/BillingRevenueQuery.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"),
            new ClassPathResource("mappers/shared/SharedReadMapper.xml"));
        factory.afterPropertiesSet(); var template=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,BillingReceiptService.class,BillingMutationService.class,
            BillingReceivableService.class,AssignmentConcurrencyService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(BillingReceiptMapper.class,()->template.getMapper(BillingReceiptMapper.class));
        context.registerBean(BillingMutationMapper.class,()->template.getMapper(BillingMutationMapper.class));
        context.registerBean(BillingRevenueQuery.class,()->template.getMapper(BillingRevenueQuery.class));
        context.registerBean(AssignmentConcurrencyMapper.class,()->template.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(SharedReadMapper.class,()->template.getMapper(SharedReadMapper.class));
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
        context.refresh(); service=context.getBean(BillingReceiptService.class);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd) VALUES(1,'P-2601','입금 검증','MO')");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd) VALUES(1,'B-2609-01',1,'2026-09','2026-09-01',100,10,'CONFIRM'),(2,'B-2609-02',1,'2026-09','2026-09-02',200,20,'CONFIRM')");
        sql("INSERT INTO bill.bill_dtl(bill_id,work_ym,mm_qty,untprc_amt,bill_amt) VALUES(1,'2026-09',1,100,100)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close(); if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);} }
    String value(String text) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('bills',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b),'details',(SELECT json_agg(d ORDER BY bill_dtl_id) FROM bill.bill_dtl d),'receipts',(SELECT json_agg(r ORDER BY rcpt_id) FROM bill.rcpt r),'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    String frozen() throws Exception {
        return value("SELECT json_build_object('bill',(SELECT row_to_json(b) FROM bill.bill b WHERE bill_id=1),'detail',(SELECT json_agg(d ORDER BY bill_dtl_id) FROM bill.bill_dtl d),'project',(SELECT json_agg(p) FROM prj.prj p))::text");
    }
    ReceiptInput input(long amount) { return new ReceiptInput(LocalDate.of(2026,10,23),amount,"분할"); }
    long create(long amount) { return service.create(1,input(amount)).receipts().get(0).receipt_id(); }
    @Test void createUpdateDeletePersistCashAndAuditWithoutTouchingRevenueOrSnapshots() throws Exception {
        String before=frozen(); long id=create(30);
        assertThat(service.list(1).billing().receivable_amt()).isEqualTo(80);
        assertThat(value("SELECT rcpt_amt FROM bill.rcpt WHERE rcpt_id="+id)).isEqualTo("30");
        assertThat(service.update(1,id,new ReceiptInput(LocalDate.of(2026,11,2),40,"수정")).billing().receivable_amt()).isEqualTo(70);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.rcpt' AND bfr_val LIKE '%30%' AND aft_val LIKE '%40%' AND chg_user_id IS NULL")).isEqualTo("1");
        assertThat(service.delete(1,id).billing().receivable_amt()).isEqualTo(110);
        assertThat(service.list(1).receipts()).isEmpty(); assertThat(service.list(1).change_history()).hasSize(3);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='DELETE' AND bfr_val LIKE '%40%' AND aft_val IS NULL")).isEqualTo("1");
        assertThat(frozen()).isEqualTo(before);
        assertThat(value("SELECT count(*) FROM bill.dun_hist")).isEqualTo("0");
    }
    @Test void confirmedAdjustmentsOnlyAndForeignReceiptAssociation() throws Exception {
        sql("INSERT INTO bill.bill(bill_no,prj_id,atrb_ym,sply_amt,vat_amt,bill_stts_cd,amnd_yn,orgnl_bill_id) VALUES('B-2610-03',1,'2026-10',-20,-2,'CONFIRM','Y',1),('B-2610-04',1,'2026-10',-50,-5,'DRAFT','Y',1)");
        long id=create(30);
        assertThat(service.list(1).billing().receivable_amt()).isEqualTo(58);
        String before=snapshot();
        assertThatThrownBy(()->service.delete(2,id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.update(2,id,input(10))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"CREATE","UPDATE","DELETE"})
    void auditFailureRollsBackAllReceiptChanges(String operation) throws Exception {
        long id=operation.equals("CREATE")?0:create(30); String before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()-> {
            switch(operation) {
                case "CREATE" -> service.create(1,input(30));
                case "UPDATE" -> service.update(1,id,input(40));
                default -> service.delete(1,id);
            }
        }).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"CREATE","UPDATE","DELETE"})
    void closedReceiptMonthLeavesAllRowsAndHistoryUnchanged(String operation) throws Exception {
        long id=operation.equals("CREATE")?0:create(30);
        sql("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");
        String before=snapshot();
        assertThatThrownBy(()-> {
            switch(operation) {
                case "CREATE" -> service.create(1,input(30));
                case "UPDATE" -> service.update(1,id,new ReceiptInput(LocalDate.of(2026,11,2),40,"새 달"));
                default -> service.delete(1,id);
            }
        }).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void concurrentCreatesCannotOverReceiveAndReadbackSurvivesNewSession() throws Exception {
        var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        Callable<String> write=()-> {
            start.await();
            try { service.create(1,input(70)); return "OK"; }
            catch(ApiException e) { return e.code(); }
        };
        try {
            var a=executor.submit(write); var b=executor.submit(write); start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("OK","OVER_RECEIPT");
            assertThat(value("SELECT sum(rcpt_amt) FROM bill.rcpt WHERE bill_id=1")).isEqualTo("70");
            assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.rcpt'")).isEqualTo("1");
            assertThat(service.list(1).billing().receivable_amt()).isEqualTo(40);
        } finally { executor.shutdownNow(); }
    }
}
