package com.sicost.common.costs;

import com.sicost.apiops.*;
import com.sicost.billingsops.*;
import java.sql.*;
import java.time.YearMonth;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CostAllocationPersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    SqlSessionTemplate session;
    ProjectCostService costs;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"),
            new ClassPathResource("mappers/billingsops/BillingMapper.xml"));
        factory.afterPropertiesSet();
        session = new SqlSessionTemplate(factory.getObject());
        costs = new ProjectCostService(session.getMapper(ProjectBoardMapper.class));
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(90001,'K-90001','배분 시험','FT')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(90001,'P-90001','배분 A','MO','RUN'),(90002,'P-90002','배분 B','TK','RUN')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,cntrct_dev_id,job_cd,bgn_dt,end_dt) VALUES(90001,'A-90001',90001,90001,NULL,'BE','2026-10-01','2026-12-31'),(90002,'A-90002',90002,90001,NULL,'BE','2026-10-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90001,'2026-10',1),(90002,'2026-10',0.5)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt,vat_amt) VALUES(90001,'2026-10','FT',100,17)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    void sql(String text) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(text); }
    }
    String value(String text) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(text)) {
            r.next(); return r.getString(1);
        }
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('salary',(SELECT json_agg(s) FROM cst.mth_cst s),'mm',(SELECT json_agg(m) FROM asgn.asgn_mth m),'allocation',(SELECT json_agg(a) FROM cst.cst_aloc a),'audit',(SELECT json_agg(h) FROM sys.data_chg_hist h))::text");
    }
    @Test void generatedCostAndJvmMatchPostgresExactLargestRemainderNoWrites() throws Exception {
        String before = snapshot();
        assertThat(costs.allocation("2026-10", 90001L).items().get(0).lbr_cst_amt()).isEqualTo(67);
        assertThat(costs.allocation("2026-10", 90002L).items().get(0).lbr_cst_amt()).isEqualTo(33);
        assertThat(value("WITH weights(id,mm) AS (VALUES(90001,1.00::numeric),(90002,0.50::numeric)), base AS (SELECT id,floor(100*mm/1.5) AS won,mod(100*mm,1.5) AS fraction FROM weights), ranked AS (SELECT id,won,row_number() OVER(ORDER BY fraction DESC,id) AS rank FROM base) SELECT (won+CASE WHEN rank=1 THEN 1 ELSE 0 END)::bigint FROM ranked WHERE id=90001"))
            .isEqualTo("67");
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void expenseIdentityVatExclusionAndCancelledMm() throws Exception {
        sql("INSERT INTO cst.drct_expns(expns_ym,dev_id,prj_id,expns_item_cd,expns_amt,vat_amt) VALUES('2026-10',90001,90001,'TRIP',100,10),('2026-10',NULL,90001,'TRIP',9,1)");
        assertThat(costs.calculate(90001, "2026-10").cumulative_cost_amt()).isEqualTo(143);
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=90002");
        assertThat(costs.calculate(90001, "2026-10").cumulative_cost_amt()).isEqualTo(209);
    }
    @Test void edgeSixClosedFrozenAndMissingSnapshotFails() throws Exception {
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        assertThatThrownBy(() -> costs.allocation("2026-10", null)).hasMessageContaining("마감 배분자료 없음");
        sql("INSERT INTO cst.cst_aloc(cst_ym,dev_id,prj_id,asgn_id,aloc_ty_cd,lbr_cst_amt) VALUES('2026-10',90001,90001,90001,'RND',19)");
        sql("UPDATE cst.mth_cst SET slry_amt=999 WHERE dev_id=90001");
        assertThat(costs.allocation("2026-10", 90001L).items().get(0).lbr_cst_amt()).isEqualTo(19);
        assertThat(costs.allocation("2026-10", 90001L).items().get(0).aloc_ty_cd()).isEqualTo("RND");
    }
    @Test void edgeFiveConfirmedProjectSupplyIncludesNegativeAmendmentsNotVatOrPersonalRevenue() throws Exception {
        sql("INSERT INTO bill.bill(bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd,amnd_yn) VALUES('B-90001',90001,'2026-10','2026-11-01',100,10,'CONFIRM','N'),('B-90002',90001,'2026-10','2026-11-01',-20,-2,'CONFIRM','Y'),('B-90003',90001,'2026-10','2026-11-01',999,99,'DRAFT','N'),('B-90004',90002,'2026-09','2026-10-01',30,3,'CONFIRM','N')");
        var mapper = session.getMapper(BillingMapper.class);
        var rows = mapper.projectRevenue(new BillingModels.RevenuePeriod(90001, "2026-01", "2026-12"));
        assertThat(rows).containsExactly(new BillingModels.ProjectRevenue(90001, "2026-10", 80));
        assertThat(mapper.projectRevenue(new BillingModels.RevenuePeriod(90002, "2026-01", "2026-12")))
            .containsExactly(new BillingModels.ProjectRevenue(90002, "2026-10", 30));
    }
    @Test void turnkeyStagePrefersActualAndIgnoresItemLines() throws Exception {
        sql("INSERT INTO prj.tk_aloc_line(aloc_line_id,prj_id,aloc_stg_cd,line_ty_cd,dev_id) VALUES(90001,90002,'EST','PERSON',90001),(90002,90002,'FINAL','PERSON',90001),(90003,90002,'ACTUAL','PERSON',90001),(90004,90002,'ACTUAL','ITEM',NULL)");
        sql("INSERT INTO prj.tk_aloc_mth(aloc_line_id,aloc_ym,aloc_amt) VALUES(90001,'2026-10',10),(90002,'2026-10',20),(90003,'2026-10',30),(90004,'2026-10',999)");
        assertThat(costs.allocation("2026-10", 90002L).items().get(0).sls_amt()).isEqualTo(30);
    }
}
