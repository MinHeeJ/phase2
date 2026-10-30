package com.sicost.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.payments.FinancePaymentModels.*;

class FinancePaymentPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    FinancePaymentService service;
    FinancePaymentMapper mapper;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/payments/FinancePaymentMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(FinancePaymentMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,FinancePaymentService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class);
        context.registerBean(FinancePaymentMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->json);
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();service=context.getBean(FinancePaymentService.class);
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    void sql(String sql) throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String value(String sql) {return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql,String.class);}
    PaymentInput input() {
        return new PaymentInput("2026-10",1,"ETC","ETC",null,null,"수령인",1000000,0,0,
            LocalDate.parse("2026-10-10"),"004","0012345678","예금주",null,null);
    }
    CompleteInput complete(Payment p,long amount) { return new CompleteInput(LocalDate.parse("2026-10-30"),amount,p.updated_at()); }
    @Test void partialCarryReCarryAndReversalPreservePlanAndExecution() {
        var parent=service.create(input());var partial=service.complete(parent.pay_id(),complete(parent,400000));
        assertThat(partial.pay_amt()).isEqualTo(1000000);assertThat(partial.remaining()).isEqualTo(600000);
        var child=service.carry(parent.pay_id(),"2026-11");assertThat(child.pay_amt()).isEqualTo(600000);
        var grandchild=service.carry(child.pay_id(),"2026-12");assertThat(grandchild.cry_pay_id()).isEqualTo(child.pay_id());
        assertThatThrownBy(()->service.complete(parent.pay_id(),new CompleteInput(LocalDate.parse("2026-10-30"),null,null)))
            .isInstanceOf(ApiException.class);
        assertThat(service.list("2026-10").summary().get(0).remaining_amount()).isZero();
        var reopened=service.reopen(parent.pay_id(),"오입력 취소");assertThat(reopened.remaining()).isEqualTo(1000000);
        assertThat(mapper.row(child.pay_id()).pay_stts_cd()).isEqualTo("CANCEL");
        assertThat(mapper.row(grandchild.pay_id()).pay_stts_cd()).isEqualTo("CANCEL");
        assertThat(value("SELECT sum(pay_amt) FROM fin.pay_execution")).isEqualTo("0");
    }
    @Test void concurrentFullCompletionsHaveExactlyOneWinner() throws Exception {
        var p=service.create(input());var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<String> action=()->{gate.await();try {service.complete(p.pay_id(),new CompleteInput(LocalDate.parse("2026-10-30"),null,null));return "OK";}
                catch(ApiException ex){return ex.code();}};
            var a=pool.submit(action);var b=pool.submit(action);gate.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","ALREADY_PAID");
            assertThat(value("SELECT count(*) FROM fin.pay_execution")).isEqualTo("1");
        } finally {pool.shutdownNow();}
    }
    @Test void stalePartialCannotExecuteTwice() {
        var p=service.create(input());service.complete(p.pay_id(),complete(p,400000));
        assertThatThrownBy(()->service.complete(p.pay_id(),complete(p,400000)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("STALE_PAYMENT"));
        assertThat(value("SELECT sum(pay_amt) FROM fin.pay_execution")).isEqualTo("400000");
    }
    @Test void auditFailureRollsBackExecutionAndState() throws Exception {
        var p=service.create(input());
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_payment_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.complete(p.pay_id(),complete(p,400000))).isInstanceOf(RuntimeException.class);
        assertThat(mapper.row(p.pay_id()).executed_amt()).isZero();assertThat(mapper.row(p.pay_id()).pay_stts_cd()).isEqualTo("PLAN");
        assertThat(value("SELECT count(*) FROM fin.pay_execution")).isEqualTo("0");
    }
    @Test void generatedSourceIsIdempotentAndPaidImmutable() throws Exception {
        sql("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd) VALUES('PAY-PERSON','개발자','FT')");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) SELECT dev_id,'2026-10','FT',1000000 FROM mst.dev WHERE mng_no='PAY-PERSON'");
        service.generate("2026-10");service.generate("2026-10");
        assertThat(value("SELECT count(*) FROM fin.pay WHERE pay_src_cd='AUTO_COST'")).isEqualTo("1");
        sql("UPDATE cst.mth_cst SET slry_amt=1200000 WHERE cst_ym='2026-10'");service.generate("2026-10");
        assertThat(service.list("2026-10").items().get(0).pay_amt()).isEqualTo(1200000);
        assertThat(service.list("2026-10").items().get(0).account()).isNull();
    }
}
