package com.sicost.billingsops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.billingsops.BillingModels.*;

class BillingPersistenceTest {
    @Configuration("billingOperationsTestTransactions") @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingCommandService commands;
    BillingMapper mapper;
    Clock clock=Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
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
        factory.setMapperLocations(new ClassPathResource("mappers/billingsops/BillingMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(BillingMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,BillingCommandService.class,BillingReceivableService.class,
            BillingAmountService.class,BillingGapService.class,BillingRevenueQuery.class,CurrentUser.class,PermissionService.class);
        context.registerBean(BillingMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->json);context.registerBean(Clock.class,()->clock);
        context.refresh();commands=context.getBean(BillingCommandService.class);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(901,'P-26901','청구 테스트','MO','MONTH')");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt) VALUES(901,'B-2610-91',901,'2026-10','2026-10-01',100000,10000)");
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String value(String sql) {return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql,String.class);}
    Amendment amendment() {return new Amendment(-10000,-1000,"감액",LocalDate.of(2026,10,2),null,null,null);}
    @Test void confirmationAmendmentReceiptAndReadbackShareAdjustedRootBalance()throws Exception {
        var confirmed=commands.confirm(901);assertThat(confirmed.billing().receivable_amt()).isEqualTo(110000);
        var child=commands.amend(901,amendment());
        assertThat(child.original_bill_id()).isEqualTo(901);assertThat(child.billing().bill_stg_id()).isNull();
        assertThat(mapper.balance(901).total()).isEqualTo(110000);
        commands.confirm(child.billing().bill_id());
        assertThat(mapper.balance(901).total()).isEqualTo(99000);
        sql("INSERT INTO bill.rcpt(bill_id,rcpt_dt,rcpt_amt) VALUES(901,'2026-11-01',20000)");
        assertThat(commands.detail(901).billing().receivable_amt()).isEqualTo(79000);
        assertThat(value("SELECT atrb_ym FROM bill.bill WHERE bill_id=901")).isEqualTo("2026-10");
        assertThat(mapper.revenue(2026)).containsExactly(new Revenue("2026-10",90000));
        assertThat(mapper.cash(2026)).containsExactly(new Cash(LocalDate.of(2026,11,1),20000));
        assertThat(value("SELECT count(*) FROM bill.dun_hist")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.bill' AND chg_user_id IS NULL")).isEqualTo("3");
        // A new mapper session materializes persisted state independently of the command's session.
        assertThat(mapper.state(901).bill_stts_cd()).isEqualTo("CONFIRM");
        assertThat(mapper.lines(child.billing().bill_id())).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"confirm","cancel","amend"})
    void auditFailureRollsBackWholeCommand(String operation)throws Exception {
        if(operation.equals("amend"))commands.confirm(901);
        String before=value("SELECT json_agg(b ORDER BY b.bill_id)::text FROM bill.bill b");
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_billing_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->{
            switch(operation) {
                case "confirm" -> commands.confirm(901);
                case "cancel" -> commands.cancel(901,"취소");
                default -> commands.amend(901,amendment());
            }
        }).isInstanceOf(RuntimeException.class);
        assertThat(value("SELECT json_agg(b ORDER BY b.bill_id)::text FROM bill.bill b")).isEqualTo(before);
    }
    @Test void cancellationPreservesRowAndRemovesConfirmedRevenue() {
        commands.confirm(901);commands.cancel(901,"계약 종료");
        assertThat(mapper.state(901).bill_stts_cd()).isEqualTo("CANCEL");
        assertThat(mapper.state(901).sply_amt()).isEqualTo(100000);
        assertThat(mapper.revenue(2026)).isEmpty();
        assertThat(commands.detail(901).billing().receivable_amt()).isZero();
    }
}
