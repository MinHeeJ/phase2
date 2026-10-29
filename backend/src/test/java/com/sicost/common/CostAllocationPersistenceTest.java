package com.sicost.common;

import com.sicost.billingsops.*;
import com.sicost.common.api.ApiException;
import com.sicost.costallocation.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static com.sicost.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CostAllocationPersistenceTest {
    @Configuration("costAllocationTestTransactions") @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    CostAllocationMapper mapper;
    BillingMapper billings;
    CostAllocationService allocations;
    Clock clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/costallocation/CostAllocationMapper.xml"),
            new ClassPathResource("mappers/billingsops/BillingMapper.xml"));
        factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(CostAllocationMapper.class);
        billings = session.getMapper(BillingMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, CostAllocationService.class, com.sicost.apiops.ProjectCostService.class);
        context.registerBean(CostAllocationMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> clock);
        context.refresh();
        allocations = context.getBean(CostAllocationService.class);
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(901,'K-00901','배분 검증','FT')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(901,'P-26901','배분 대상','MO','RUN'),(902,'P-26902','다른 배분 대상','DP','RUN')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(901,'A-00901',901,901,'BE','2026-01-01','2026-12-31'),(902,'A-00902',902,901,'BE','2026-01-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(901,'2026-10',1.00),(902,'2026-10',0.50)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(901,'2026-10','FT',100)");
    }

    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context != null) context.close();
        if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }
    void sql(String query) { new JdbcTemplate(source).execute(query); }
    String value(String query) { return new JdbcTemplate(source).queryForObject(query, String.class); }
    String snapshot() {
        return value("SELECT json_build_object('mm',(SELECT json_agg(m ORDER BY asgn_id,work_ym) FROM asgn.asgn_mth m),"
            + "'cost',(SELECT json_agg(c ORDER BY mth_cst_id) FROM cst.mth_cst c),"
            + "'expense',(SELECT json_agg(e ORDER BY expns_id) FROM cst.drct_expns e),"
            + "'snapshot',(SELECT json_agg(c ORDER BY cst_aloc_id) FROM cst.cst_aloc c))::text");
    }

    @Test void realSourceMaterializationConservesWonExcludesVatAndDoesNotWrite() {
        sql("INSERT INTO cst.drct_expns(expns_ym,dev_id,prj_id,expns_item_cd,expns_amt,vat_amt) VALUES('2026-10',901,901,'TEAM',100,10),('2026-10',NULL,901,'TRIP',11,1)");
        String before = snapshot();
        var result = allocations.calculate("2026-10", null);
        assertThat(result.items()).extracting(AllocationRow::lbr_cst_amt).containsExactly(67L, 33L, 0L);
        assertThat(result.items()).extracting(AllocationRow::drct_expns_amt).containsExactly(67L, 33L, 11L);
        assertThat(result.items().get(0).mm_qty()).isEqualTo(new BigDecimal("1.00"));
        assertThat(result.items().get(1).mm_qty()).isEqualTo(new BigDecimal("0.50"));
        assertThat(context.getBean(com.sicost.apiops.ProjectCostService.class).calculate(901, "2026-10").cumulative_cost_amt()).isEqualTo(145);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void closedSnapshotSurvivesNewMapperSessionAndIgnoresAllMutableSources() {
        sql("INSERT INTO cst.cst_aloc(cst_ym,dev_id,prj_id,asgn_id,aloc_ty_cd,mm_qty,lbr_cst_amt,drct_expns_amt) VALUES('2026-09',901,901,901,'PROJECT',0.40,17,3)");
        var first = allocations.calculate("2026-09", 901L);
        sql("UPDATE cst.mth_cst SET slry_amt=200 WHERE dev_id=901");
        sql("UPDATE asgn.asgn_mth SET mm_qty=0.20 WHERE asgn_id=901");
        assertThat(allocations.calculate("2026-09", 901L)).isEqualTo(first);
        assertThat(mapper.snapshots("2026-09").get(0).cost()).isEqualTo(20);
        assertThat(allocations.calculate("2026-10", 901L).items().get(0).lbr_cst_amt()).isEqualTo(40);
    }

    @Test void closedMissingSnapshotNeverInsertsOrUsesLiveSalary() {
        String before = snapshot();
        assertThatThrownBy(() -> allocations.calculate("2026-09", 901L))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("PART_STANDARD_MISSING"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void turnkeyUsesLatestProjectStageAndPersonRowsOnly() {
        sql("UPDATE prj.prj SET cntrct_ty_cd='TK' WHERE prj_id=901");
        sql("INSERT INTO prj.tk_aloc_line(aloc_line_id,prj_id,aloc_stg_cd,line_ty_cd,dev_id) VALUES(901,901,'EST','PERSON',901),(902,901,'FINAL','PERSON',901),(903,901,'ACTUAL','PERSON',901),(904,901,'ACTUAL','ITEM',NULL)");
        sql("INSERT INTO prj.tk_aloc_mth(aloc_line_id,aloc_ym,aloc_amt) VALUES(901,'2026-10',1000),(902,'2026-10',2000),(903,'2026-10',3000),(904,'2026-10',9999)");
        assertThat(mapper.turnkey("2026-10")).containsExactly(new Turnkey(901, 901, 3000L));
        assertThat(allocations.calculate("2026-10", 901L).items().get(0).sls_amt()).isEqualTo(3000);
        sql("DELETE FROM prj.tk_aloc_mth WHERE aloc_line_id=903");
        assertThat(mapper.turnkey("2026-10")).isEmpty();
        assertThat(allocations.calculate("2026-10", 901L).warnings()).anyMatch(w -> w.contains("턴키 배분표 없음"));
    }

    @Test void confirmedProjectSupplyIncludesNegativeAmendmentNeverCashOrVat() {
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd) VALUES(901,'B-2610-901',901,'2026-10','2026-10-01',1000,100,'CONFIRM'),(902,'B-2610-902',902,'2026-10','2026-10-01',2000,200,'CONFIRM'),(903,'B-2610-903',901,'2026-10','2026-10-01',9000,900,'DRAFT')");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd,amnd_yn,orgnl_bill_id) VALUES(904,'B-2610-904',901,'2026-10','2026-10-02',-100,-10,'CONFIRM','Y',901)");
        sql("INSERT INTO bill.rcpt(bill_id,rcpt_dt,rcpt_amt) VALUES(901,'2026-11-01',500)");
        var query = new BillingModels.CostRevenueQuery(2026, 901L);
        assertThat(billings.projectRevenue(query)).containsExactly(new BillingModels.ProjectRevenue(901, "2026-10", 900));
        assertThat(billings.revenue(2026)).containsExactly(new BillingModels.Revenue("2026-10", 2900));
        assertThat(billings.cash(2026)).containsExactly(new BillingModels.Cash(LocalDate.of(2026, 11, 1), 500));
        assertThat(value("SELECT mm_qty FROM asgn.asgn_mth WHERE asgn_id=901 AND work_ym='2026-10'")).isEqualTo("1.00");
    }

    @Test void tkSupplyUsesBillingMonthNotAttributionAndMoUsesAttribution() {
        sql("UPDATE prj.prj SET cntrct_ty_cd='TK' WHERE prj_id=901");
        sql("INSERT INTO bill.bill(bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd) VALUES('B-2610-901',901,'2026-09','2026-10-01',1000,100,'CONFIRM'),('B-2610-902',902,'2026-09','2026-10-01',2000,200,'CONFIRM')");
        assertThat(billings.projectRevenue(new BillingModels.CostRevenueQuery(2026, null))).containsExactly(
            new BillingModels.ProjectRevenue(901, "2026-10", 1000), new BillingModels.ProjectRevenue(902, "2026-09", 2000));
    }

    @Test void postgresNumericLargestRemainderMatchesJvmWithoutIntegerDivisionOrEarlyRounding() {
        var jdbc = new JdbcTemplate(source);
        for (long amount : new long[]{1, 2, 100, 9007199254740993L}) {
            var db = jdbc.queryForList("WITH weights(id,mm) AS (VALUES(1,1.00::numeric),(2,0.50::numeric)),"
                + "shares AS (SELECT id,?::numeric*mm/sum(mm) OVER() AS exact FROM weights),"
                + "parts AS (SELECT id,trunc(exact) AS base,exact-trunc(exact) AS fraction FROM shares),"
                + "ranked AS (SELECT id,base,row_number() OVER(ORDER BY fraction DESC,id) AS rank,"
                + "?::numeric-sum(base) OVER() AS remaining FROM parts)"
                + " SELECT (base+CASE WHEN rank<=remaining THEN 1 ELSE 0 END)::bigint FROM ranked ORDER BY id", Long.class, amount, amount);
            var jvm = com.sicost.common.validation.CostRemainderAllocator.allocate(amount, List.of(
                new com.sicost.common.validation.CostRemainderAllocator.Weight(1L, new BigDecimal("1.00")),
                new com.sicost.common.validation.CostRemainderAllocator.Weight(2L, new BigDecimal("0.50"))));
            assertThat(db).containsExactlyElementsOf(jvm);
        }
        assertThat(jdbc.queryForObject("SELECT trunc(-1.99::numeric)::bigint", Long.class))
            .isEqualTo(com.sicost.common.validation.CostValues.signedWon(new BigDecimal("-1.99")));
    }
}
