package com.sicost.tax;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.finance.*;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.tax.CorporateTaxModels.*;

class CorporateTaxPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    CorporateTaxService service;
    CorporateTaxMapper mapper;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {com.sicost.support.TestDatabaseDdl.create(statement,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/tax/CorporateTaxMapper.xml"),new ClassPathResource("mappers/finance/PaymentExecutionMapper.xml"));factory.afterPropertiesSet();
        var template=new SqlSessionTemplate(factory.getObject());mapper=template.getMapper(CorporateTaxMapper.class);
        var executions=template.getMapper(PaymentExecutionMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,CorporateTaxService.class,PaymentExecutionService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(CorporateTaxMapper.class,()->mapper);context.registerBean(PaymentExecutionMapper.class,()->executions);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->json);
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2031-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();service=context.getBean(CorporateTaxService.class);
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {com.sicost.support.TestDatabaseDdl.drop(statement,database);}
    }
    void sql(String sql) throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String value(String sql){return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql,String.class);}
    PaymentInput payment(LocalDate actual,Long amount){return new PaymentInput("FINAL",LocalDate.of(2031,10,15),5000000,actual,amount,null);}
    @Test void planPersistsHistoryAndNeverWritesMonthlySnapshots() throws Exception {
        service.savePlan(2031,new PlanInput(100,"최초"));service.savePlan(2031,new PlanInput(120,"수정"));
        var result=service.plan(2031);assertThat(result.annual_amount()).isEqualTo(120);
        assertThat(result.history()).hasSize(2);assertThat(result.history().get(0).before_amount()).isEqualTo(100);
        assertThat(result.history().get(0).after_amount()).isEqualTo(120);assertThat(result.history().get(0).reason()).isEqualTo("수정");
        assertThat(result.months().stream().mapToLong(m->m.amount()).sum()).isEqualTo(120);
        assertThat(value("SELECT count(*) FROM fin.corp_tax_mth WHERE tax_yr='2031'")).isEqualTo("0");
    }
    @Test void failedHistoryRollsBackAnnualChange() throws Exception {
        service.savePlan(2031,new PlanInput(100,"최초"));
        sql("ALTER TABLE fin.corp_tax_expc_hist ADD CONSTRAINT reject_tax_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.savePlan(2031,new PlanInput(120,"수정"))).isInstanceOf(RuntimeException.class);
        assertThat(service.plan(2031).annual_amount()).isEqualTo(100);assertThat(service.plan(2031).history()).hasSize(1);
    }
    @Test void naturalUpsertReturnsStableGeneratedIdAndKeepsYearAcrossDates() throws Exception {
        service.savePayments(2031,List.of(payment(null,null)));long id=service.payments(2031).payments().get(0).tax_pay_id();
        service.savePayments(2031,List.of(new PaymentInput("FINAL",LocalDate.of(2032,3,31),6000000,null,null,null)));
        assertThat(service.payments(2031).payments()).hasSize(1);assertThat(service.payments(2031).payments().get(0).tax_pay_id()).isEqualTo(id);
        assertThat(value("SELECT tax_yr FROM fin.corp_tax_pay WHERE tax_pay_id="+id)).isEqualTo("2031");
        assertThat(service.payments(2032).payments()).isEmpty();
    }
    long linkedPayment() throws Exception {
        service.savePayments(2031,List.of(payment(null,null)));
        long taxId=service.payments(2031).payments().get(0).tax_pay_id();
        sql("INSERT INTO fin.pay(pay_ym,pay_se_cd,rcvr_se_cd,rcvr_nm,sply_amt,pay_amt,pay_plan_dt,pay_src_cd,src_rec_id,bank_cd,acnt_no,dpstr_nm) VALUES('2031-10','CORP_TAX','AGENCY','시험 납부 기관',5000000,5000000,'2031-10-15','AUTO_FIN',"+taxId+",'BANK','1234567890','시험 기관')");
        return taxId;
    }
    @Test void linkedTaxCreatesOneFiveMillionExecutionAndKeepsOriginalPlan() throws Exception {
        long taxId=linkedPayment();service.savePayments(2031,List.of(payment(LocalDate.of(2031,10,16),5000000L)));
        assertThat(value("SELECT sum(pay_amt) FROM fin.pay_execution")).isEqualTo("5000000");
        assertThat(value("SELECT count(*) FROM fin.pay_execution")).isEqualTo("1");
        assertThat(value("SELECT pay_amt FROM fin.pay WHERE src_rec_id="+taxId)).isEqualTo("5000000");
        assertThat(value("SELECT pay_stts_cd FROM fin.pay WHERE src_rec_id="+taxId)).isEqualTo("PAID");
        assertThat(service.payments(2031).payments().get(0).actual_amount()).isEqualTo(5000000);
    }
    @Test void failedTaxAuditRollsBackExecutionAndBothRows() throws Exception {
        long taxId=linkedPayment();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_tax_audit CHECK(tbl_nm!='fin.corp_tax_pay') NOT VALID");
        assertThatThrownBy(()->service.savePayments(2031,List.of(payment(LocalDate.of(2031,10,16),5000000L)))).isInstanceOf(RuntimeException.class);
        assertThat(value("SELECT count(*) FROM fin.pay_execution")).isEqualTo("0");
        assertThat(value("SELECT pay_stts_cd FROM fin.pay WHERE src_rec_id="+taxId)).isEqualTo("PLAN");
        assertThat(service.payments(2031).payments().get(0).actual_amount()).isNull();
    }
    @Test void closedPaymentRejectsWithoutHistoryOrPlanMutation() throws Exception {
        linkedPayment();sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2031-10','CLOSED')");
        String before=value("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.savePayments(2031,List.of(payment(LocalDate.of(2031,10,16),5000000L))))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(before);
        assertThat(service.payments(2031).payments().get(0).actual_date()).isNull();
    }
}
