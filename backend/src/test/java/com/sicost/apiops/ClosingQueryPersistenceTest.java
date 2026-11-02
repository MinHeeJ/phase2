package com.sicost.apiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ClosingQueryPersistenceTest {
    @Configuration("closingQueryTransactions") @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ClosingQueryMapper mapper;
    ClosingQueryService service;
    JdbcTemplate jdbc;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl,user,password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement,database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/apiops/ClosingQueryMapper.xml")); factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ClosingQueryMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class,ClosingQueryService.class);
        context.registerBean(ClosingQueryMapper.class, () -> mapper);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.refresh(); service = context.getBean(ClosingQueryService.class);
        jdbc = new JdbcTemplate(source);
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl,user,password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    void sql(String sql) { jdbc.execute(sql); }
    String value(String sql) { return jdbc.queryForObject(sql,String.class); }
    Map<String,String> snapshot() {
        Map<String,String> rows = new LinkedHashMap<>();
        for (String table : List.of("fin.mth_cls", "fin.mth_cls_chk", "fin.mth_cls_chk_hist", "fin.mth_cls_hist",
                "cst.mth_cst", "asgn.asgn_mth", "bill.bill", "fin.entry", "fin.pay", "cst.prvmn_adj",
                "cst.cst_aloc", "fin.mth_pl", "fin.mth_pl_dtl", "fin.corp_tax_mth", "fin.cash_bal",
                "sys.acs_hist", "sys.data_chg_hist")) {
            rows.put(table,value("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM " + table + " t"));
        }
        return rows;
    }
    void checks(String month) {
        sql("INSERT INTO fin.mth_cls(cls_ym) VALUES('" + month + "')");
        sql("INSERT INTO fin.mth_cls_chk(cls_ym,cls_chk_cd,chk_rslt_cd,issue_cnt,msg_desc,chk_at) "
            + "SELECT '"+month+"',dtl_cd,CASE WHEN dtl_cd='MM_OVER' THEN 'WARN' ELSE 'OK' END,"
            + "CASE WHEN dtl_cd='MM_OVER' THEN 2 ELSE 0 END,'저장된 설명','"+month+"-28 09:30:00' "
            + "FROM cmn.cd_dtl WHERE cd_grp_id='CLOSE_CHECK'");
    }
    void closed() {
        checks("2035-01");
        sql("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED',cls_at='2035-02-01 09:00:00' WHERE cls_ym='2035-01'");
        sql("INSERT INTO fin.mth_pl(pl_ym,pl_src_cd) VALUES('2035-01','CLOSE')");
        sql("INSERT INTO fin.corp_tax_plan(tax_yr,annl_expc_amt) VALUES('2035',0)");
        sql("INSERT INTO fin.corp_tax_mth(tax_ym,tax_yr,corp_tax_amt) VALUES('2035-01','2035',0)");
        sql("INSERT INTO fin.cash_bal(bal_ym,bgn_bal_amt,end_bal_amt) VALUES('2035-01',0,0)");
        jdbc.update("INSERT INTO sys.acs_hist(acs_ty_cd,scrn_id,obj_desc,dtl_desc) VALUES('CLOSE','UI-14','2035-01',?)",
            "{\"snapshots\":" + mapper.snapshotEvidence("2035-01") + "}");
    }
    @Test void switchingMonthsReturnsStoredNineAndDoesNotChangeAnySourceOrCheckOrAudit() {
        checks("2035-01"); checks("2035-02");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(95001,'CLOSING-95001','조회 검증','FT')");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(95001,'2035-01','FT',1000)");
        Map<String,String> before = snapshot();
        for (String month : List.of("2035-01","2035-02")) {
            var result = service.getClosing(month);
            assertThat(result.checks()).hasSize(9);
            assertThat(result.checkedAt().toString()).isEqualTo(month+"-28T09:30+09:00");
            assertThat(result.checks().get(1).count()).isEqualTo(2);
            assertThat(result.warningCount()).isEqualTo(1);
        }
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void uncheckedAbsentMonthDoesNotCreateState() {
        Map<String,String> before = snapshot();
        var result = service.getClosing("2036-01");
        assertThat(result.status()).isEqualTo("OPEN"); assertThat(result.checks()).isEmpty();
        assertThat(result.checkedAt()).isNull(); assertThat(snapshot()).isEqualTo(before);
    }
    @Test void completeZeroDetailManifestIsValidAndNullableActorIsNotFabricated() {
        closed(); Map<String,String> before = snapshot();
        var result = service.getClosing("2035-01");
        assertThat(result.status()).isEqualTo("CLOSED"); assertThat(result.canReopen()).isTrue();
        assertThat(result.actorId()).isNull(); assertThat(result.actorName()).isNull();
        assertThat(result.processedAt().toString()).isEqualTo("2035-02-01T09:00+09:00");
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"fin.mth_pl","fin.corp_tax_mth","fin.cash_bal"})
    void missingClosedDomainIsConflictAndNeverRebuilt(String table) {
        closed(); sql("DELETE FROM " + table + " WHERE " + (table.equals("fin.mth_pl") ? "pl_ym" : table.equals("fin.cash_bal") ? "bal_ym" : "tax_ym") + "='2035-01'");
        Map<String,String> before = snapshot();
        assertThatThrownBy(() -> service.getClosing("2035-01")).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("MISSING_CLOSED_SNAPSHOT"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void removedNonemptyDetailsAreNotMistakenForLegitimateEmptySnapshot() {
        closed(); sql("INSERT INTO fin.mth_pl_dtl(pl_ym,dim_ty_cd,dim_val,sls_amt) VALUES('2035-01','PROJECT','95001',100)");
        jdbc.update("UPDATE sys.acs_hist SET dtl_desc=? WHERE acs_ty_cd='CLOSE' AND obj_desc='2035-01'",
            "{\"snapshots\":" + mapper.snapshotEvidence("2035-01") + "}");
        sql("DELETE FROM fin.mth_pl_dtl WHERE pl_ym='2035-01'");
        assertThatThrownBy(() -> service.getClosing("2035-01")).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("MISSING_CLOSED_SNAPSHOT"));
    }
    @Test void activeReferenceBlocksReopenButCancelledReferenceDoesNot() {
        closed(); sql("INSERT INTO cst.prvmn_adj(aply_ym,orgnl_ym,adj_se_cd,adj_amt,rsn_desc) VALUES('2035-02','2035-01','REVENUE',100,'조정')");
        assertThat(service.getClosing("2035-01").canReopen()).isFalse();
        sql("UPDATE cst.prvmn_adj SET adj_stts_cd='CANCELLED',cncl_rsn_desc='취소',cncl_at=CURRENT_TIMESTAMP WHERE orgnl_ym='2035-01'");
        assertThat(service.getClosing("2035-01").canReopen()).isTrue();
    }
    @Test void olderClosedMonthCannotReopen() {
        closed(); sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2035-02','CLOSED')");
        assertThat(service.getClosing("2035-01").canReopen()).isFalse();
    }
    @Test void historicSeedWithoutSnapshotsIsReportedNotRepaired() {
        var before = snapshot();
        assertThatThrownBy(() -> service.getClosing("2026-09")).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("MISSING_CLOSED_SNAPSHOT"));
        assertThat(snapshot()).isEqualTo(before);
    }
}
