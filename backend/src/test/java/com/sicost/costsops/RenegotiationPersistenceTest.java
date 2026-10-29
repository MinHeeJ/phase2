package com.sicost.costsops;

import com.sicost.apiops.*;
import com.sicost.common.costs.CostStandards;
import com.sicost.common.security.PermissionService;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RenegotiationPersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    RenegotiationService service;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl,user,password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"),
            new ClassPathResource("mappers/costsops/RenegotiationMapper.xml"));
        factory.afterPropertiesSet(); var session = new SqlSessionTemplate(factory.getObject());
        var board = session.getMapper(ProjectBoardMapper.class);
        service = new RenegotiationService(session.getMapper(RenegotiationMapper.class),new ProjectCostService(board),
            new CostStandards(board),new PermissionService(),Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")));
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(90001,'K-90001','재협상 시험','FT')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(90001,'P-90001','진행 대상','MO','RUN'),(90002,'P-90002','분모 대상','TK','RUN')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(90001,'A-90001',90001,90001,'BE','2026-10-01','2027-04-23'),(90002,'A-90002',90002,90001,'BE','2026-10-01','2027-04-23')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90001,'2026-10',1),(90002,'2026-10',0.5)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(90001,'2026-10-01',100)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(90001,'2026-10','FT',135)");
        sql("INSERT INTO cmn.oprt_std(oprt_std_id,aply_bgn_ym,oprt_std_nm,num_val) VALUES('TARGET_MARGIN_RATE','2026-10','시험 목표',20) ON CONFLICT(oprt_std_id,aply_bgn_ym) DO UPDATE SET num_val=excluded.num_val");
    }
    @AfterEach void removeDatabase() throws Exception {
        if (source == null) return;
        try (var c=DriverManager.getConnection(adminUrl,user,password); var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception {
        try (var c=source.getConnection(); var s=c.createStatement()) { s.execute(text); }
    }
    String snapshot() throws Exception {
        try (var c=source.getConnection(); var s=c.createStatement(); var r=s.executeQuery(
            "SELECT json_build_object('salary',(SELECT json_agg(s) FROM cst.mth_cst s),'assignments',(SELECT json_agg(a) FROM asgn.asgn a),'mm',(SELECT json_agg(m) FROM asgn.asgn_mth m),'rates',(SELECT json_agg(u) FROM asgn.untprc_hist u),'snapshots',(SELECT json_agg(a) FROM cst.cst_aloc a),'audit',(SELECT json_agg(h) FROM sys.data_chg_hist h),'access',(SELECT json_agg(h) FROM sys.acs_hist h),'close',(SELECT json_agg(f) FROM fin.mth_cls f))::text")) {
            r.next(); return r.getString(1);
        }
    }
    @Test void realProjectionAndCrossProjectDenominatorSurviveProjectFilterWithoutWrites() throws Exception {
        String before=snapshot();
        var result=service.list(RenegotiationQuery.parse(Map.of("projectId","90001","empType","FT","size","1")));
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().get(0).allocated_cost_amt()).isEqualTo(90);
        assertThat(result.items().get(0).margin_rt()).isEqualByComparingTo("10");
        assertThat(result.summary().annual_increase_amt()).isEqualByComparingTo("60");
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"TK","IN"}) void excludedContractType(String type) throws Exception {
        sql("UPDATE prj.prj SET cntrct_ty_cd='"+type+"' WHERE prj_id=90001");
        assertThat(service.list(RenegotiationQuery.parse(Map.of())).items()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"PLAN","HOLD","END","LOST"}) void onlyRunningProject(String status) throws Exception {
        sql("UPDATE prj.prj SET prj_stts_cd='"+status+"' WHERE prj_id=90001");
        assertThat(service.list(RenegotiationQuery.parse(Map.of())).items()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"CANCELLED","ENDED","REPLACED"}) void onlyActiveAssignment(String status) throws Exception {
        sql("UPDATE asgn.asgn SET asgn_stts_cd='"+status+"' WHERE asgn_id=90001");
        assertThat(service.list(RenegotiationQuery.parse(Map.of())).items()).isEmpty();
    }
    @Test void freeAssignmentIsExcluded() throws Exception {
        sql("UPDATE asgn.asgn SET asgn_se_cd='FREE' WHERE asgn_id=90001");
        assertThat(service.list(RenegotiationQuery.parse(Map.of())).items()).isEmpty();
    }
    @Test void freeProjectIsExcluded() throws Exception {
        sql("UPDATE prj.prj SET free_sprt_yn='Y' WHERE prj_id=90001");
        assertThat(service.list(RenegotiationQuery.parse(Map.of())).items()).isEmpty();
    }
    @Test void expiredAssignmentIsExcluded() throws Exception {
        sql("UPDATE asgn.asgn SET end_dt='2026-10-22' WHERE asgn_id=90001");
        assertThat(service.list(RenegotiationQuery.parse(Map.of())).items()).isEmpty();
    }
    @Test void futureStartIsExcluded() throws Exception {
        sql("UPDATE asgn.asgn SET bgn_dt='2026-10-24' WHERE asgn_id=90001");
        assertThat(service.list(RenegotiationQuery.parse(Map.of())).items()).isEmpty();
    }
    @Test void renewalWindowIsInclusiveAndThreeMonthsExcludeFarRenewal() throws Exception {
        assertThat(service.list(RenegotiationQuery.parse(Map.of("windowMonths","3"))).items()).isEmpty();
        assertThat(service.list(RenegotiationQuery.parse(Map.of())).items()).hasSize(1);
        sql("UPDATE asgn.asgn SET end_dt='2027-04-24' WHERE asgn_id=90001");
        assertThat(service.list(RenegotiationQuery.parse(Map.of())).items()).isEmpty();
    }
    @Test void openRateChangeRefreshesButClosedSnapshotNeverChanges() throws Exception {
        sql("UPDATE asgn.untprc_hist SET bill_untprc_amt=105 WHERE asgn_id=90001");
        assertThat(service.list(RenegotiationQuery.parse(Map.of())).items().get(0).bill_untprc_amt()).isEqualTo(105);
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        sql("INSERT INTO cst.cst_aloc(cst_ym,dev_id,prj_id,asgn_id,aloc_ty_cd,mm_qty,tot_mm_qty,lbr_cst_amt,bill_untprc_amt) VALUES('2026-10',90001,90001,90001,'PROJECT',1,1.5,90,100)");
        String before=snapshot();
        assertThat(service.list(RenegotiationQuery.parse(Map.of())).items().get(0).bill_untprc_amt()).isEqualTo(100);
        assertThat(snapshot()).isEqualTo(before);
    }
}
