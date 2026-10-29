package com.sicost.common;

import com.sicost.common.api.*;
import com.sicost.common.costallocation.*;
import com.sicost.common.security.PermissionService;
import com.sicost.billingsops.*;
import java.math.BigDecimal;
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
import static com.sicost.common.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CostAllocationPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    CostAllocationService service;
    BillingRevenueQuery revenue;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/costallocation/CostAllocationMapper.xml"),
            new ClassPathResource("mappers/billingsops/BillingMapper.xml"));
        factory.afterPropertiesSet(); var session = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        CostAllocationTestSupport.register(context, session);
        context.register(com.sicost.apiops.ProjectCostService.class, com.sicost.projectcost.ProjectCostService.class);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        var clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        context.registerBean(Clock.class, () -> clock); context.refresh();
        service = context.getBean(CostAllocationService.class);
        revenue = new BillingRevenueQuery(session.getMapper(BillingMapper.class), null, clock, new PermissionService());
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(90001,'K-90001','배분 검증 인력','FT')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(90001,'P-26901','배분 프로젝트 A','MO','RUN'),(90002,'P-26902','배분 프로젝트 B','MO','RUN')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(90001,'A-90001',90001,90001,'BE','2026-10-01','2026-10-31'),(90002,'A-90002',90002,90001,'BE','2026-10-01','2026-10-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90001,'2026-10',1.00),(90002,'2026-10',0.50)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(90001,'2026-10','FT',100)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    void sql(String text) throws Exception { try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(text); } }
    String value(String text) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(text)) { r.next(); return r.getString(1); }
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('salary',(SELECT json_agg(s ORDER BY mth_cst_id) FROM cst.mth_cst s),'mm',(SELECT json_agg(m ORDER BY asgn_id,work_ym) FROM asgn.asgn_mth m),'snapshots',(SELECT json_agg(c ORDER BY cst_aloc_id) FROM cst.cst_aloc c),'audit',(SELECT count(*) FROM sys.data_chg_hist),'access',(SELECT count(*) FROM sys.acs_hist))::text");
    }
    @Test void postgresAndJvmLargestRemaindersAgreeAndBothAdaptersUseTheEngine() throws Exception {
        String before = snapshot();
        var rows = service.get("2026-10", null).items();
        String expected = value("WITH weights(id,w) AS (VALUES(90001,1.00::numeric),(90002,0.50::numeric)), shares AS (SELECT id,100*w/sum(w) OVER() AS amount FROM weights), ranked AS (SELECT id,floor(amount) AS base,row_number() OVER(ORDER BY amount-floor(amount) DESC,id) AS rn,100-sum(floor(amount)) OVER() AS residual FROM shares) SELECT string_agg((base+CASE WHEN rn<=residual THEN 1 ELSE 0 END)::text,',' ORDER BY id) FROM ranked");
        assertThat(rows).extracting(Allocation::lbr_cst_amt).containsExactly(67L, 33L);
        assertThat(String.join(",", rows.stream().map(r -> r.lbr_cst_amt().toString()).toList())).isEqualTo(expected);
        assertThat(context.getBean(com.sicost.apiops.ProjectCostService.class).calculate(90001, "2026-10").cumulative_cost_amt()).isEqualTo(67);
        assertThat(context.getBean(com.sicost.projectcost.ProjectCostService.class).calculate(90001, YearMonth.of(2026, 10)).cumulativeCostAmount()).isEqualTo(67);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void snapshotEmployeeTypeOverridesCurrentDeveloperAndPointFourBoundaryMatchesSql() throws Exception {
        sql("DELETE FROM asgn.asgn_mth WHERE asgn_id=90002");
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=90002");
        sql("UPDATE asgn.asgn_mth SET mm_qty=0.40 WHERE asgn_id=90001");
        sql("UPDATE mst.dev SET emp_se_cd='OS' WHERE dev_id=90001");
        var employee = service.get("2026-10", null).items();
        assertThat(employee).extracting(Allocation::lbr_cst_amt).containsExactly(40L, 60L);
        assertThat(value("SELECT floor(indv_lbr_cst_amt::numeric*0.40)::text FROM cst.mth_cst")).isEqualTo("40");
        sql("UPDATE cst.mth_cst SET emp_se_cd='FL' WHERE dev_id=90001");
        assertThat(service.get("2026-10", null).items()).extracting(Allocation::lbr_cst_amt).containsExactly(100L);
    }
    @Test void personalExpenseWithProjectIdIsNotAlsoCountedAsProjectExpenseAndVatIsExcluded() throws Exception {
        sql("INSERT INTO cst.drct_expns(expns_ym,dev_id,prj_id,expns_item_cd,expns_amt,vat_amt) VALUES('2026-10',90001,90001,'TEAM',100,999),('2026-10',NULL,90001,'TRIP',9,999)");
        var result = service.get("2026-10", 90001L);
        assertThat(result.items()).extracting(Allocation::drct_expns_amt).containsExactly(67L);
        assertThat(result.project_expenses()).extracting(ProjectExpense::drct_expns_amt).containsExactly(9L);
        assertThat(service.projectTotal(90001, "2026-10").amount()).isEqualTo(143);
    }
    @Test void openReadsRecalculateMmAndPriceWhileSourceMmRemainsUntouchedByBilling() throws Exception {
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(90001,'2026-10-01',1000)");
        var first = service.get("2026-10", 90001L).items().get(0);
        assertThat(first.sls_amt()).isEqualTo(1000); assertThat(first.mm_qty()).isEqualByComparingTo("1.00");
        sql("UPDATE asgn.asgn_mth SET mm_qty=0.50 WHERE asgn_id=90001");
        var second = service.get("2026-10", 90001L).items().get(0);
        assertThat(second.lbr_cst_amt()).isEqualTo(50); assertThat(second.sls_amt()).isEqualTo(500);
        sql("INSERT INTO bill.bill(bill_no,prj_id,atrb_ym,bill_stts_cd,sply_amt) VALUES('B-2610-9001',90001,'2026-10','CONFIRM',400)");
        assertThat(revenue.revenue(90001, "2026-10")).isEqualTo(400);
        assertThat(service.get("2026-10", 90001L).items().get(0)).isEqualTo(second);
        assertThat(value("SELECT mm_qty FROM asgn.asgn_mth WHERE asgn_id=90001")).isEqualTo("0.50");
    }
    @Test void closedMissingRelevantDeveloperProjectSnapshotNeverFallsBackOrWrites() throws Exception {
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        sql("INSERT INTO cst.cst_aloc(cst_ym,dev_id,prj_id,asgn_id,aloc_ty_cd,lbr_cst_amt) VALUES('2026-10',90001,90001,90001,'PROJECT',987)");
        String before = snapshot();
        assertThat(service.get("2026-10", 90001L).items()).extracting(Allocation::lbr_cst_amt).containsExactly(987L);
        assertThatThrownBy(() -> service.get("2026-10", null)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.code()).isEqualTo("CLOSED_MONTH"); assertThat(ex.getMessage()).isEqualTo("마감 배분자료 없음");
        });
        assertThat(snapshot()).isEqualTo(before);
        sql("UPDATE cst.mth_cst SET slry_amt=99999 WHERE dev_id=90001");
        assertThat(service.get("2026-10", 90001L).items()).extracting(Allocation::lbr_cst_amt).containsExactly(987L);
    }
    @Test void turnkeyReferenceUsesHighestStagePersonOnlyAndNeverCountsTheSamePersonMonthTwice() throws Exception {
        sql("UPDATE prj.prj SET cntrct_ty_cd='TK' WHERE prj_id=90001");
        sql("UPDATE asgn.asgn SET prj_id=90001 WHERE asgn_id=90002");
        sql("INSERT INTO prj.tk_aloc_line(aloc_line_id,prj_id,aloc_stg_cd,line_ty_cd,dev_id) VALUES(90001,90001,'EST','PERSON',90001),(90002,90001,'FINAL','PERSON',90001),(90003,90001,'ACTUAL','PERSON',90001),(90004,90001,'ACTUAL','ITEM',NULL)");
        sql("INSERT INTO prj.tk_aloc_mth(aloc_line_id,aloc_ym,aloc_amt) VALUES(90001,'2026-10',100),(90002,'2026-10',200),(90003,'2026-10',300),(90004,'2026-10',999)");
        assertThat(service.get("2026-10", 90001L).items()).extracting(Allocation::sls_amt).containsExactly(200L, 100L);
    }
    @Test void confirmedNegativeAmendmentAffectsCompanyRevenueOnlyAndNotCashOrPersonalReference() throws Exception {
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_stts_cd,sply_amt,vat_amt) VALUES(90001,'B-2610-9001',90001,'2026-10','CONFIRM',1000,100),(90002,'B-2610-9002',90001,'2026-10','DRAFT',9999,999)");
        sql("INSERT INTO bill.bill(bill_no,prj_id,atrb_ym,bill_stts_cd,amnd_yn,orgnl_bill_id,sply_amt,vat_amt) VALUES('B-2610-9003',90001,'2026-10','CONFIRM','Y',90001,-100,-10)");
        String before = snapshot();
        assertThat(revenue.revenue(90001, "2026-10")).isEqualTo(900);
        assertThat(revenue.revenue(90002, "2026-10")).isZero();
        assertThat(service.get("2026-10", 90001L).items().get(0).sls_amt()).isNull();
        assertThat(snapshot()).isEqualTo(before);
    }
}
