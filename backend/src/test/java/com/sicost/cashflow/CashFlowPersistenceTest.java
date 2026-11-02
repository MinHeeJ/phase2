package com.sicost.cashflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.billingsops.BillingRevenueQuery;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static com.sicost.cashflow.CashFlowModels.*;

class CashFlowPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String url,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    CashFlowService service;
    CashFlowMapper mapper;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        url=System.getenv("TEST_DATABASE_URL");
        assumeTrue(url!=null&&!url.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(url.substring(0,url.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/cashflow/CashFlowMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(CashFlowMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,CashFlowService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class);
        context.registerBean(CashFlowMapper.class,()->mapper);context.registerBean(BillingRevenueQuery.class,()->mock(BillingRevenueQuery.class));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));context.registerBean(ObjectMapper.class,()->json);context.refresh();service=context.getBean(CashFlowService.class);
    }
    @AfterEach void cleanup()throws Exception {if(context!=null)context.close();if(source==null)return;try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s,database);}}
    void sql(String value)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(value);}}
    String value(String query){return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(query,String.class);}
    Inflow input(Long id,String plan,String actual,long amount){return new Inflow(id,plan.substring(0,7),amount,"LOAN","대여금",LocalDate.parse(plan),actual==null?null:LocalDate.parse(actual),"비고");}
    @Test void generatedIdentityRoundTripAndAtomicHistory()throws Exception {
        var created=service.saveInflow(input(null,"2026-10-15",null,0),false);
        assertThat(created.inflw_id()).isPositive();assertThat(mapper.inflows()).contains(created);
        var changed=service.saveInflow(input(created.inflw_id(),"2026-11-15","2026-10-20",400000),true);
        assertThat(changed.inflw_id()).isEqualTo(created.inflw_id());
        assertThat(value("SELECT inflw_amt FROM fin.etc_inflw WHERE inflw_id="+created.inflw_id())).isEqualTo("400000");
        assertThat(value("SELECT bfr_val::jsonb->>'amount' FROM sys.data_chg_hist WHERE tbl_nm='fin.etc_inflw' AND data_chg_ty_cd='UPDATE'")).isEqualTo("0");
        assertThat(value("SELECT aft_val::jsonb->>'amount' FROM sys.data_chg_hist WHERE tbl_nm='fin.etc_inflw' AND data_chg_ty_cd='UPDATE'")).isEqualTo("400000");
    }
    @Test void auditFailureRollsBackCreateUpdateAndInitial()throws Exception {
        var created=service.saveInflow(input(null,"2026-10-15",null,10),false);
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_cash_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.saveInflow(input(null,"2026-10-15",null,30),false)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.saveInflow(input(created.inflw_id(),"2026-10-15",null,40),true)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.saveOpening(new Opening("2026-10",100L))).isInstanceOf(RuntimeException.class);
        assertThat(mapper.initial()).isNull();assertThat(mapper.inflows()).containsExactly(created);
    }
    @Test void openingIsGlobalAndNeverWritesClosing()throws Exception {
        service.saveOpening(new Opening("2026-10",100L));
        assertThatThrownBy(()->service.saveOpening(new Opening("2027-01",0L))).isInstanceOf(ApiException.class);
        assertThat(mapper.initial().amount()).isEqualTo(100);
        service.flow(2027,"BOTH");assertThat(value("SELECT end_bal_amt FROM fin.cash_bal WHERE init_yn='Y'")).isNull();
    }
    @Test void closedOriginalAndTargetDoNotChange()throws Exception {
        var created=service.saveInflow(input(null,"2026-10-15",null,10),false);
        assertThatThrownBy(()->service.saveInflow(input(created.inflw_id(),"2026-09-15",null,20),true)).isInstanceOf(ApiException.class);
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        assertThatThrownBy(()->service.saveInflow(input(created.inflw_id(),"2026-11-15",null,30),true)).isInstanceOf(ApiException.class);
        assertThat(mapper.inflows()).containsExactly(created);
    }
    @Test void executionCarryTaxAndEntryUseSourceIdentityOnce()throws Exception {
        service.saveOpening(new Opening("2026-10",2000000L));
        sql("INSERT INTO fin.entry(entry_id,entry_ym,fin_se_cd,item_nm,entry_amt,vat_amt,inout_dt,inout_cmpl_yn) VALUES(800,'2026-10','SGA','지출',900000,100000,'2026-10-10','Y'),(801,'2026-10','NOI','삭제',500,0,'2026-10-10','Y')");
        sql("UPDATE fin.entry SET deleted_yn='Y' WHERE entry_id=801");
        sql("INSERT INTO fin.pay(pay_id,pay_ym,pay_se_cd,rcvr_se_cd,rcvr_nm,pay_amt,pay_plan_dt,pay_stts_cd,pay_src_cd,src_rec_id) VALUES(900,'2026-10','SGA','ETC','수령인',1000000,'2026-10-10','UNPAID','AUTO_FIN',800)");
        sql("INSERT INTO fin.pay_execution(pay_id,pay_dt,pay_amt) VALUES(900,'2026-10-12',400000)");
        sql("INSERT INTO fin.pay(pay_id,pay_ym,pay_se_cd,rcvr_se_cd,rcvr_nm,pay_amt,pay_plan_dt,pay_stts_cd,pay_src_cd,cry_pay_id) VALUES(901,'2026-11','SGA','ETC','수령인',600000,'2026-11-10','PLAN','AUTO_FIN',900)");
        sql("INSERT INTO fin.corp_tax_pay(tax_pay_id,tax_yr,tax_pay_se_cd,pay_plan_dt,plan_amt,pay_dt,pay_amt) VALUES(800,'2026','FINAL','2026-10-15',50000,'2026-10-15',50000)");
        sql("INSERT INTO fin.pay(pay_id,pay_ym,pay_se_cd,rcvr_se_cd,rcvr_nm,pay_amt,pay_plan_dt,pay_stts_cd,pay_src_cd,src_rec_id) VALUES(902,'2026-10','CORP_TAX','AGENCY','기관',50000,'2026-10-15','PAID','AUTO_FIN',800)");
        sql("INSERT INTO fin.pay_execution(pay_id,pay_dt,pay_amt) VALUES(902,'2026-10-15',50000)");
        var flow=service.flow(2026,"BOTH");var oct=flow.months().get(9);var nov=flow.months().get(10);
        assertThat(oct.actual_outflow()).isEqualTo(450000);assertThat(oct.forecast_closing()).isEqualTo(1550000);
        assertThat(nov.forecast_closing()).isEqualTo(950000);assertThat(nov.planned_outflow()).isEqualTo(600000);
        assertThat(oct.planned_outflow()).isEqualTo(450000);assertThat(oct.actual_inflow()).isZero();
    }
    @Test void billingAmendmentsAndPartialReceiptsPreserveOriginalPlan()throws Exception {
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd) VALUES(700,'P-2699','현금','MO')");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,sply_amt,vat_amt,bill_stts_cd,rcpt_plan_dt) VALUES(710,'B-2610-99',700,100,10,'CONFIRM','2026-10-20')");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,sply_amt,vat_amt,bill_stts_cd,amnd_yn,orgnl_bill_id) VALUES(711,'B-2610-98',700,-10,-1,'CONFIRM','Y',710)");
        sql("INSERT INTO bill.rcpt(bill_id,rcpt_dt,rcpt_amt) VALUES(710,'2026-10-21',40)");
        assertThat(mapper.events()).anySatisfy(e->{assertThat(e.planned_amount()).isEqualTo(99);assertThat(e.remaining_amount()).isEqualTo(59);assertThat(e.inflow()).isTrue();});
    }
    @Test void simultaneousInitialRequestsCreateOnlyOneGlobalOpening()throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CyclicBarrier(2);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<String>>();
            for(String month:List.of("2026-10","2027-01"))futures.add(pool.submit(()->{ready.await();try{service.saveOpening(new Opening(month,100L));return "SAVED";}catch(ApiException e){return e.code();}}));
            var results=new ArrayList<String>();for(var future:futures)results.add(future.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("SAVED","OPENING_BALANCE_REQUIRED");
            assertThat(value("SELECT count(*) FROM fin.cash_bal WHERE init_yn='Y'")).isEqualTo("1");
            assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='fin.cash_bal'")).isEqualTo("1");
        } finally {pool.shutdownNow();}
    }
}
