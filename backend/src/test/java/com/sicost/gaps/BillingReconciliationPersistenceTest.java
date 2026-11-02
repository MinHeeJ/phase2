package com.sicost.gaps;

import com.sicost.billingsops.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static com.sicost.billingsops.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

class BillingReconciliationPersistenceTest {
    @Configuration("billingReconciliationTestTransactions") @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingGapService service;
    BillingExportController exports;
    BillingMapper mapper;

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
        factory.setMapperLocations(new ClassPathResource("mappers/billingsops/BillingMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(BillingMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,BillingGapService.class,BillingAmountService.class,
            BillingExportController.class,CurrentUser.class,PermissionService.class,ExcelExportService.class);
        context.registerBean(BillingMapper.class,()->mapper);
        context.registerBean(BillingReceivableService.class,()->mock(BillingReceivableService.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();service=context.getBean(BillingGapService.class);exports=context.getBean(BillingExportController.class);
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(990901,'K-990901','정산 검증','FT')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(990901,'P-990901','정산 검증 프로젝트','MO','MONTH')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(990901,'A-990901',990901,990901,'BE','2026-10-01','2026-10-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(990901,'2026-10',1)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(990901,'2026-10-01',2000000)");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    void sql(String value) {new JdbcTemplate(source).execute(value);}
    long count(String value) {return new JdbcTemplate(source).queryForObject(value,Long.class);}
    Gaps list() {return service.list(BillingInput.gaps(Map.of("month","2026-10","q","정산 검증")));}
    void bill(long id,String status,long amount) {
        new JdbcTemplate(source).update("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd) VALUES(?,?,990901,'2026-10','2026-11-01',?,0,?)",
            id,"B-"+id,amount,status);
    }
    @Test void mapperMaterializesSourcesAndConfirmedSupplyByAttributionMonth() {
        bill(990901,"CONFIRM",2100000);bill(990902,"CONFIRM",-100000);
        bill(990903,"DRAFT",500000);bill(990904,"PLAN",500000);bill(990905,"CANCEL",500000);
        var result=list();
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().get(0).confirmed_supply_amt()).isEqualTo(2000000);
        assertThat(result.items().get(0).judgment()).isEqualTo("맞음");
        assertThat(result.items().get(0).billing_ids()).containsExactly(990901L,990902L);
        assertThat(mapper.sources(new Scope(990901,"2026-10",null)).get(0).price()).isEqualTo(2000000);
    }
    @Test void cancelledAssignmentsAndNonMonthlyContractsAreExcluded() {
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=990901");
        assertThat(list().items()).isEmpty();
        sql("UPDATE asgn.asgn SET asgn_stts_cd='ACTIVE' WHERE asgn_id=990901");
        sql("UPDATE prj.prj SET cntrct_ty_cd='TK' WHERE prj_id=990901");
        assertThat(list().items()).isEmpty();
    }
    @Test void dispatchMissingAndStoredAttendanceUseSameRealSourceQuery() {
        sql("UPDATE prj.prj SET cntrct_ty_cd='DP',stlm_mthd_cd='DAY' WHERE prj_id=990901");
        assertThat(list().items().get(0).judgment()).isEqualTo("근태 미입력");
        sql("INSERT INTO bill.dspch_atnd(work_ym,prj_id,dev_id,asgn_id,work_day_qty) VALUES('2026-10',990901,990901,990901,2)");
        assertThat(list().items().get(0).basis_supply_amt()).isEqualTo(4000000);
        assertThat(list().items().get(0).attendance_missing()).isFalse();
    }
    @Test void listDoesNotCreateMonthCheckHistoryOrAuditRows() {
        long months=count("SELECT count(*) FROM fin.mth_cls");
        long checks=count("SELECT count(*) FROM fin.mth_cls_chk");
        long history=count("SELECT count(*) FROM sys.acs_hist");
        list();list();
        assertThat(count("SELECT count(*) FROM fin.mth_cls")).isEqualTo(months);
        assertThat(count("SELECT count(*) FROM fin.mth_cls_chk")).isEqualTo(checks);
        assertThat(count("SELECT count(*) FROM sys.acs_hist")).isEqualTo(history);
    }
    @Test void downloadCommitsOnlyNullableActorAccessReceipt() {
        var result=exports.exportBillingGaps(Map.of("month","2026-10","q","정산 검증"));
        assertThat(result.getBody()).isNotEmpty();
        assertThat(count("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-13' AND user_id IS NULL AND obj_desc='청구누락.xlsx'")).isEqualTo(1);
        assertThat(list().items().get(0).judgment()).isEqualTo("청구 없음");
    }
    @Test void auditFailureReturnsNoWorkbookAndLeavesSourcesUnchanged() {
        long before=count("SELECT count(*) FROM sys.acs_hist");
        sql("ALTER TABLE sys.acs_hist ADD CONSTRAINT reject_reconciliation_download CHECK(false) NOT VALID");
        assertThatThrownBy(()->exports.exportBillingGaps(Map.of("month","2026-10"))).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM sys.acs_hist")).isEqualTo(before);
        assertThat(list().items().get(0).basis_supply_amt()).isEqualTo(2000000);
    }
}
