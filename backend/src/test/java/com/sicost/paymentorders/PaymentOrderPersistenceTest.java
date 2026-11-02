package com.sicost.paymentorders;

import com.sicost.common.security.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static com.sicost.paymentorders.PaymentOrderModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PaymentOrderPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PaymentOrderService service;
    PaymentOrderMapper mapper;
    @BeforeEach void setup()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/paymentorders/PaymentOrderMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(PaymentOrderMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,PaymentOrderService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(PaymentOrderMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.refresh();service=context.getBean(PaymentOrderService.class);
    }
    @AfterEach void cleanup()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s,database);}
    }
    void sql(String text)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);}}
    String value(String text) {return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(text,String.class);}
    List<Order> input() {return List.of(new Order(1,LocalDate.parse("2028-02-12"),false));}
    @Test void defaultsReadWithoutWritesAndLeapMonthEnd() {
        assertThat(service.list("2028-02").items()).extracting(Order::planned_date).containsExactly(
            LocalDate.parse("2028-02-10"),LocalDate.parse("2028-02-15"),LocalDate.parse("2028-02-20"),LocalDate.parse("2028-02-29"));
        assertThat(value("SELECT count(*) FROM fin.pay_odr WHERE pay_ym='2028-02'")).isEqualTo("0");
    }
    @Test void savesOnlyTargetMonthAndKeepsPlanAndHeaderConsistent()throws Exception {
        sql("INSERT INTO fin.pay(pay_ym,pay_odr_no,pay_se_cd,rcvr_se_cd,rcvr_nm,pay_amt,pay_plan_dt) VALUES('2028-02',1,'ETC','ETC','시험',100,'2028-02-10')");
        service.save("2028-02",input());
        assertThat(value("SELECT pay_plan_dt FROM fin.pay WHERE pay_ym='2028-02'")).isEqualTo("2028-02-12");
        assertThat(service.list("2028-02").items().get(0).planned_date()).hasToString("2028-02-12");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm IN ('fin.pay','fin.pay_odr')")).isEqualTo("2");
    }
    @Test void auditFailureRollsBackBothHeaderAndPayment()throws Exception {
        sql("INSERT INTO fin.pay(pay_ym,pay_odr_no,pay_se_cd,rcvr_se_cd,rcvr_nm,pay_amt,pay_plan_dt) VALUES('2028-02',1,'ETC','ETC','시험',100,'2028-02-10')");
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.save("2028-02",input())).isInstanceOf(RuntimeException.class);
        assertThat(value("SELECT count(*) FROM fin.pay_odr WHERE pay_ym='2028-02'")).isEqualTo("0");
        assertThat(value("SELECT pay_plan_dt FROM fin.pay WHERE pay_ym='2028-02'")).isEqualTo("2028-02-10");
    }
    @Test void paidAndClosedNeverMutate()throws Exception {
        sql("INSERT INTO fin.pay(pay_ym,pay_odr_no,pay_se_cd,rcvr_se_cd,rcvr_nm,pay_amt,pay_plan_dt,pay_stts_cd,pay_dt) VALUES('2028-02',1,'ETC','ETC','시험',100,'2028-02-10','PAID','2028-02-11')");
        assertThatThrownBy(()->service.save("2028-02",input())).isInstanceOfSatisfying(com.sicost.common.api.ApiException.class,e->assertThat(e.code()).isEqualTo("ALREADY_PAID"));
        assertThat(value("SELECT pay_dt FROM fin.pay WHERE pay_ym='2028-02'")).isEqualTo("2028-02-11");
        assertThat(value("SELECT count(*) FROM fin.pay_odr WHERE pay_ym='2028-02'")).isEqualTo("0");
    }
}
