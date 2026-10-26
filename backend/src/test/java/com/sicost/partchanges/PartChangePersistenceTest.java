package com.sicost.partchanges;

import static com.sicost.partchanges.PartChangeModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.api.ApiExceptionHandler;
import com.sicost.common.api.RequestIdFilter;
import com.sicost.common.security.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PartChangePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PartChangeService service;
    PartChangeMapper mapper;

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var s = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/partchanges/PartChangeMapper.xml")); factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PartChangeMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, PartChangeService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(PartChangeMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.refresh(); service = context.getBean(PartChangeService.class);
        sql("INSERT INTO mst.bsn_part(bsn_part_id,bsn_part_nm,hq_yn,use_yn) VALUES(101,'A파트','N','Y'),(102,'B파트','N','Y'),(103,'시험 본사','Y','Y'),(104,'중지 파트','N','N')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES(101,'P-2691','파트 변경 대상','MO','RUN',101),(102,'P-2692','다른 프로젝트','MO','RUN',101)");
        sql("INSERT INTO prj.prj_part_hist(prj_id,aply_bgn_ym,bsn_part_id) VALUES(101,'2026-03',101),(102,'2026-03',101)");
        sql("UPDATE fin.mth_cls SET cls_stts_cd='OPEN'");
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-09','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var s = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    void sql(String sql) throws Exception { try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(sql); } }
    String value(String sql) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            assertThat(r.next()).isTrue(); return r.getString(1);
        }
    }
    long scalar(String sql) throws Exception { return Long.parseLong(value(sql)); }
    PartChangeRequest input(long part, String month, String reason) { return new PartChangeRequest(part, month, reason); }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('project',(SELECT row_to_json(p) FROM prj.prj p WHERE prj_id=101),'history',(SELECT json_agg(h ORDER BY part_hist_id) FROM prj.prj_part_hist h),'audit',(SELECT json_agg(a ORDER BY data_chg_hist_id) FROM sys.data_chg_hist a))::text");
    }
    long attributed(String month) throws Exception {
        try (var c = source.getConnection(); var s = c.prepareStatement("SELECT h.bsn_part_id FROM prj.prj_part_hist h WHERE h.prj_id=101 AND h.aply_bgn_ym<=? ORDER BY h.aply_bgn_ym DESC LIMIT 1")) {
            s.setString(1, month); try (var r = s.executeQuery()) { assertThat(r.next()).isTrue(); return r.getLong(1); }
        }
    }
    @Test void realUpsertMaterializesNullInitialReasonAndAuditWithMonthlyAttribution() throws Exception {
        var initial = service.list(101).get(0);
        assertThat(initial.rsn_desc()).isNull(); assertThat(initial.chg_at()).isNotNull(); assertThat(initial.partName()).isEqualTo("A파트");
        var result = service.create(101, input(102, "2026-10", "담당 파트 조정"));
        assertThat(result).hasSize(2); assertThat(result.get(1).partName()).isEqualTo("B파트");
        assertThat(mapper.project(101).bsn_part_id()).isEqualTo(102);
        assertThat(attributed("2026-09")).isEqualTo(101); assertThat(attributed("2026-10")).isEqualTo(102);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.prj_part_hist' AND obj_rec_id='101' AND chg_user_id IS NULL")).isEqualTo(1);
        assertThat(value("SELECT aft_val FROM sys.data_chg_hist WHERE obj_rec_id='101'")).contains("B파트", "2026-10", "담당 파트 조정");
        assertThat(service.list(102)).hasSize(1);
    }
    @Test void anonymousPostUpsertAndGetReadBackDatabaseSideEffects() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var mvc = MockMvcBuilders.standaloneSetup(new PartChangeController(service, json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        mvc.perform(post("/api/projects/101/part-changes").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input(102, "2026-10", "담당 변경"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[1].bsn_part_id").value(102));
        assertThat(scalar("SELECT count(*) FROM prj.prj_part_hist WHERE prj_id=101")).isEqualTo(2);
        assertThat(scalar("SELECT bsn_part_id FROM prj.prj WHERE prj_id=101")).isEqualTo(102);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.prj_part_hist' AND obj_rec_id='101' AND chg_user_id IS NULL")).isEqualTo(1);
        mvc.perform(get("/api/projects/101/part-changes")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[1].partName").value("B파트"));
        String before = snapshot();
        mvc.perform(post("/api/projects/101/part-changes").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input(103, "2026-09", "마감 거절"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void correctingSameMonthRetainsIdentityAndLatestFutureRepresentative() throws Exception {
        service.create(101, input(102, "2026-10", "첫 변경"));
        long identity = scalar("SELECT part_hist_id FROM prj.prj_part_hist WHERE prj_id=101 AND aply_bgn_ym='2026-10'");
        service.create(101, input(103, "2026-10", "본사 정정"));
        assertThat(service.list(101)).hasSize(2);
        assertThat(scalar("SELECT part_hist_id FROM prj.prj_part_hist WHERE prj_id=101 AND aply_bgn_ym='2026-10'")).isEqualTo(identity);
        assertThat(value("SELECT bfr_val FROM sys.data_chg_hist ORDER BY data_chg_hist_id DESC LIMIT 1")).contains("B파트", "첫 변경");
        service.create(101, input(102, "2027-05", "미래 담당"));
        service.create(101, input(101, "2026-11", "과거 미마감 정정"));
        assertThat(mapper.project(101).bsn_part_id()).isEqualTo(102);
        assertThat(attributed("2026-11")).isEqualTo(101);
        assertThat(attributed("2027-05")).isEqualTo(102);
    }
    @Test void closedMonthRejectionLeavesCompleteSnapshotUnchanged() throws Exception {
        String before = snapshot();
        assertThatThrownBy(() -> service.create(101, input(102, "2026-09", "변경")))
            .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status()).isEqualTo(409); assertThat(e.code()).isEqualTo("CLOSED_MONTH"); });
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void blankReasonLeavesCompleteSnapshotUnchanged() throws Exception {
        String before = snapshot();
        assertThatThrownBy(() -> service.create(101, input(102, "2026-10", " "))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void inactivePartLeavesCompleteSnapshotUnchanged() throws Exception {
        String before = snapshot();
        assertThatThrownBy(() -> service.create(101, input(104, "2026-10", "변경"))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void internalProjectLeavesCompleteSnapshotUnchanged() throws Exception {
        sql("UPDATE prj.prj SET cntrct_ty_cd='IN',bsn_part_id=NULL WHERE prj_id=101"); String before = snapshot();
        assertThatThrownBy(() -> service.create(101, input(102, "2026-10", "변경"))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void disabledProjectLeavesCompleteSnapshotUnchanged() throws Exception {
        sql("UPDATE prj.prj SET use_yn='N' WHERE prj_id=101"); String before = snapshot();
        assertThatThrownBy(() -> service.create(101, input(102, "2026-10", "변경"))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before); assertThat(service.list(101)).hasSize(1);
    }
    @Test void auditFailureRollsBackHistoryAndRepresentativeTogether() throws Exception {
        String before = snapshot(); sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(() -> service.create(101, input(102, "2026-10", "변경"))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void existingMonthlyWorkAndBillingDataAreNeverChanged() throws Exception {
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(101,'K-0191','시험 개발자','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(101,'A-0191',101,101,'BE','2026-03-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(101,'2026-09',1.00),(101,'2026-10',1.00)");
        sql("INSERT INTO bill.bill(bill_no,prj_id,atrb_ym,sply_amt) VALUES('B-0191',101,'2026-09',1000000)");
        String months = value("SELECT json_agg(m ORDER BY work_ym)::text FROM asgn.asgn_mth m");
        String bills = value("SELECT json_agg(b ORDER BY bill_id)::text FROM bill.bill b");
        service.create(101, input(102, "2026-10", "담당 변경"));
        assertThat(value("SELECT json_agg(m ORDER BY work_ym)::text FROM asgn.asgn_mth m")).isEqualTo(months);
        assertThat(value("SELECT json_agg(b ORDER BY bill_id)::text FROM bill.bill b")).isEqualTo(bills);
        assertThat(attributed("2026-09")).isEqualTo(101); assertThat(attributed("2026-10")).isEqualTo(102);
    }
}
