package com.sicost.billingsops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
import static com.sicost.billingsops.BillingReviewModels.*;

class BillingReviewPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingLifecycleService service;
    BillingReviewMapper mapper;
    BillingRevenueQuery revenue;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/billingsops/BillingReviewMapper.xml"),
            new ClassPathResource("mappers/common/BillingRevenueQuery.xml"),new ClassPathResource("mappers/common/BillingMutationMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"),new ClassPathResource("mappers/shared/SharedReadMapper.xml"));
        factory.afterPropertiesSet();var template=new SqlSessionTemplate(factory.getObject());
        mapper=template.getMapper(BillingReviewMapper.class);revenue=template.getMapper(BillingRevenueQuery.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,BillingLifecycleService.class,BillingMutationService.class,
            AssignmentConcurrencyService.class,BillingReceivableService.class,BillingAmountService.class,
            CurrentUser.class,PermissionService.class);
        context.registerBean(BillingReviewMapper.class,()->mapper);
        context.registerBean(BillingRevenueQuery.class,()->revenue);
        context.registerBean(BillingMutationMapper.class,()->template.getMapper(BillingMutationMapper.class));
        context.registerBean(AssignmentConcurrencyMapper.class,()->template.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(SharedReadMapper.class,()->template.getMapper(SharedReadMapper.class));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.refresh();service=context.getBean(BillingLifecycleService.class);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(901,'P-26901','청구 생애 검증','MO','MONTH')");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd) VALUES(901,'B-2610-01',901,'2026-10','2026-10-23',100000,10000,'DRAFT')");
        sql("INSERT INTO bill.bill_dtl(bill_id,work_ym,bill_amt) VALUES(901,'2026-10',99000)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {com.sicost.support.TestDatabaseDdl.drop(s,database);}
    }
    void sql(String text) throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);}}
    String value(String text) throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}}
    String snapshot() throws Exception {return value("SELECT json_build_object('b',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b),'d',(SELECT json_agg(d ORDER BY bill_dtl_id) FROM bill.bill_dtl d),'h',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");}
    Amendment input(long supply) {return new Amendment(supply,supply/10,"정산 감액",LocalDate.of(2026,10,23),null,null,null);}
    @Test void confirmAndAmendPreserveSnapshotsAndOnlyConfirmedChildChangesBalance() throws Exception {
        var original=service.confirm(901);String details=value("SELECT json_agg(d)::text FROM bill.bill_dtl d WHERE bill_id=901");
        var child=service.amend(901,input(-10000));long id=child.billing().bill_id();
        assertThat(child.original_bill_id()).isEqualTo(901L);assertThat(child.billing().bill_no()).isNotEqualTo(original.billing().bill_no());
        assertThat(child.billing().bill_stg_id()).isNull();assertThat(child.billing().bill_stts_cd()).isEqualTo("DRAFT");
        assertThat(revenue.balance(901).adjustedTotal()).isEqualTo(110000);
        assertThat(mapper.details(id)).hasSize(1);assertThat(mapper.details(id).get(0).work_ym()).isEqualTo("2026-10");
        service.confirm(id);assertThat(revenue.balance(901).adjustedTotal()).isEqualTo(99000);
        assertThat(value("SELECT json_agg(d)::text FROM bill.bill_dtl d WHERE bill_id=901")).isEqualTo(details);
        assertThat(value("SELECT sply_amt FROM bill.bill WHERE bill_id=901")).isEqualTo("100000");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.bill' AND chg_user_id IS NULL")).isEqualTo("5");
    }
    @Test void cancellationPersistsReasonAndExcludesRevenueWithoutDeletingRow() throws Exception {
        service.confirm(901);service.cancel(901,"청구 오류");
        assertThat(value("SELECT bill_stts_cd FROM bill.bill WHERE bill_id=901")).isEqualTo("CANCEL");
        assertThat(revenue.monthlyRevenue(new com.sicost.common.billing.BillingSupportModels.Period("2026-10","2026-10"))).isEmpty();
        assertThat(revenue.balance(901).receivable()).isZero();
        assertThat(value("SELECT rsn_desc FROM sys.data_chg_hist WHERE aft_val='CANCEL'")).isEqualTo("청구 오류");
    }
    @ParameterizedTest @ValueSource(strings={"confirm","cancel","amendments"})
    void auditFailureRollsBackAllBusinessChanges(String action) throws Exception {
        if(action.equals("amendments"))service.confirm(901);
        String before=snapshot();sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->{switch(action){case "confirm"->service.confirm(901);case "cancel"->service.cancel(901,"취소");default->service.amend(901,input(-10000));}}).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void amendmentConfirmationRechecksReceiptBalanceAndRollsBack() throws Exception {
        service.confirm(901);var child=service.amend(901,input(-10000));
        sql("INSERT INTO bill.rcpt(bill_id,rcpt_dt,rcpt_amt) VALUES(901,'2026-10-23',105000)");
        String before=snapshot();assertThatThrownBy(()->service.confirm(child.billing().bill_id()))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("REFUND_REQUIRED"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void concurrentConfirmationHasOneWinnerAndOneStaleBill() throws Exception {
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        Callable<String> confirm=()->{start.await();try{service.confirm(901);return "CONFIRM";}catch(ApiException e){return e.code();}};
        try{var a=executor.submit(confirm);var b=executor.submit(confirm);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("CONFIRM","STALE_BILL");
            assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE aft_val='CONFIRM'")).isEqualTo("1");
        }finally{executor.shutdownNow();}
    }
}
