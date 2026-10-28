package com.sicost.billingsops;

import static com.sicost.billingsops.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;

class BillingPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingService service;
    BillingMapper mapper;
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
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/billingsops/BillingMapper.xml"));
        factory.afterPropertiesSet();mapper=new SqlSessionTemplate(factory.getObject()).getMapper(BillingMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,BillingService.class,BillingReceivableService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(BillingMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
        context.refresh();service=context.getBean(BillingService.class);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(900001,'P-BILL-TEST','청구 시험','MO','MONTH')");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd) VALUES(900001,'B-2610-01',900001,'2026-10','2026-10-01',100000,10000,'DRAFT')");
        sql("INSERT INTO bill.bill_dtl(bill_id,work_ym,bill_amt) VALUES(900001,'2026-10',95000)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);}}
    String value(String text) throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}}
    String snapshot() throws Exception {
        return value("SELECT json_build_object('b',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b),'d',(SELECT json_agg(d ORDER BY bill_dtl_id) FROM bill.bill_dtl d),'h',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),'p',(SELECT row_to_json(p) FROM prj.prj p WHERE prj_id=900001))::text");
    }
    Amendment amendment() {return new Amendment(new java.math.BigDecimal("-10000"),new java.math.BigDecimal("-1000"),"감액",LocalDate.of(2026,10,23),null,null,null);}
    @Test void confirmPreservesSnapshotAndSourceAndReconfirmIsConflict() throws Exception {
        String details=value("SELECT json_agg(d)::text FROM bill.bill_dtl d");
        service.confirm(900001);
        assertThat(value("SELECT bill_stts_cd FROM bill.bill WHERE bill_id=900001")).isEqualTo("CONFIRM");
        assertThat(value("SELECT json_agg(d)::text FROM bill.bill_dtl d")).isEqualTo(details);
        String before=snapshot();assertThatThrownBy(()->service.confirm(900001)).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void amendmentDraftDoesNotChangeReceivableThenConfirmationDoes() throws Exception {
        service.confirm(900001); Bill original=mapper.bill(900001);
        var child=service.amend(900001,amendment());long id=child.billing().bill_id();
        assertThat(child.billing().bill_no()).isEqualTo("B-2610-02");
        assertThat(mapper.bill(900001)).isEqualTo(original);
        assertThat(service.detail(900001).billing().receivable_amt()).isEqualTo(110000);
        assertThat(mapper.details(id)).hasSize(1);
        service.confirm(id);
        assertThat(service.detail(900001).billing().receivable_amt()).isEqualTo(99000);
        assertThatThrownBy(()->service.cancel(900001,new Cancellation("취소"))).isInstanceOf(ApiException.class);
        service.cancel(id,new Cancellation("감액 취소"));
        assertThat(service.detail(900001).billing().receivable_amt()).isEqualTo(110000);
    }
    @ParameterizedTest @ValueSource(strings={"confirm","cancel","amend"})
    void auditFailureRollsBackEveryWrite(String operation) throws Exception {
        if(operation.equals("amend"))service.confirm(900001);
        String before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_billing_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->{
            switch(operation){case "confirm" -> service.confirm(900001);case "cancel" -> service.cancel(900001,new Cancellation("취소"));default -> service.amend(900001,amendment());}
        }).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void twoConcurrentConfirmationsOnlyOneCommits() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        Callable<String> confirm=()->{start.await();try{service.confirm(900001);return "OK";}catch(ApiException ex){return ex.code();}};
        try {
            var one=pool.submit(confirm);var two=pool.submit(confirm);start.countDown();
            assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","STALE_BILL");
            assertThat(value("SELECT COUNT(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.bill' AND obj_rec_id='900001'")).isEqualTo("1");
        } finally {pool.shutdownNow();}
    }
    @Test void allQueryProjectionsMaterializeAgainstPostgresql() {
        assertThat(mapper.project(900001).project_name()).isEqualTo("청구 시험");
        assertThat(mapper.gapProjects("2026-10")).isNotNull();
        assertThat(mapper.sources(new SourceKey(900001,"2026-10"))).isEmpty();
        assertThat(mapper.receipts(900001)).isEmpty();
        assertThat(mapper.history(900001)).isEmpty();
        assertThat(mapper.revenue(2026)).isEmpty();
        assertThat(mapper.cash(2026)).isNotNull();
        assertThat(mapper.virtualPlanned(2026)).isGreaterThanOrEqualTo(0);
    }
}
