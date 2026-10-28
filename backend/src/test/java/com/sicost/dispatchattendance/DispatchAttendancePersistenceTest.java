package com.sicost.dispatchattendance;

import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DispatchAttendancePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    DispatchAttendanceService service;
    DispatchAttendanceMapper mapper;
    MockMvc mvc;
    ObjectMapper json;
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
        factory.setMapperLocations(new ClassPathResource("mappers/dispatchattendance/DispatchAttendanceMapper.xml"));
        factory.afterPropertiesSet(); mapper = new SqlSessionTemplate(factory.getObject()).getMapper(DispatchAttendanceMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, DispatchAttendanceService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(DispatchAttendanceMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.refresh(); service = context.getBean(DispatchAttendanceService.class);
        json = context.getBean(ObjectMapper.class).copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new DispatchAttendanceController(service, json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd,cntrct_bgn_dt,cntrct_end_dt) VALUES(1,'P-ATTEND','근태 시험','DP','DAY','2026-01-01','2026-12-31')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(10,'D-ATTEND','근태 인력','FT'),(11,'D-OMITTED','보존 인력','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,untprc_base_cd) VALUES(20,'A-ATTEND',1,10,'DEV','2026-01-01','2026-12-31','DAY'),(21,'A-OMITTED',1,11,'DEV','2026-01-01','2026-12-31','DAY')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(20,'2026-10',1.00),(21,'2026-10',1.00)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close();
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
    Input input(long assignment, long developer, String days) {
        return new Input(assignment, developer, new BigDecimal(days), null, null, new BigDecimal("8"));
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('attendance',(SELECT json_agg(t ORDER BY atnd_id) FROM bill.dspch_atnd t),'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),'mm',(SELECT json_agg(m ORDER BY asgn_id) FROM asgn.asgn_mth m),'price',(SELECT json_agg(p ORDER BY asgn_id) FROM asgn.untprc_hist p))::text");
    }
    @Test void anonymousUpsertMaterializesRoundsAndPreservesOmittedRowsAndSources() throws Exception {
        service.save("2026-10", 1L, new Batch(List.of(input(21, 11, "3"))));
        String mm = value("SELECT json_agg(m ORDER BY asgn_id)::text FROM asgn.asgn_mth m");
        mvc.perform(put("/api/dispatch-attendance").param("month", "2026-10").param("projectId", "1")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Batch(List.of(input(20, 10, "1"))))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].calculated_mm").value(0.13))
            .andExpect(jsonPath("$.data.items[0].price_available").value(false));
        service.save("2026-10", 1L, new Batch(List.of(input(20, 10, "2"))));
        assertThat(value("SELECT count(*) FROM bill.dspch_atnd")).isEqualTo("2");
        assertThat(value("SELECT work_day_qty FROM bill.dspch_atnd WHERE dev_id=11")).isEqualTo("3.0");
        assertThat(value("SELECT work_day_qty FROM bill.dspch_atnd WHERE dev_id=10")).isEqualTo("2.0");
        assertThat(value("SELECT json_agg(m ORDER BY asgn_id)::text FROM asgn.asgn_mth m")).isEqualTo(mm);
        assertThat(value("SELECT count(*) FROM asgn.untprc_hist")).isEqualTo("0");
        assertThat(value("SELECT bfr_val::json->>'work_day_qty' FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).isEqualTo("1.0");
        assertThat(value("SELECT aft_val::json->>'work_day_qty' FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).isEqualTo("2");
    }
    @Test void auditFailureRollsBackAllAttendanceAndAudit() throws Exception {
        service.save("2026-10", 1L, new Batch(List.of(input(20, 10, "1"))));
        String before = snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_attendance_history CHECK(false) NOT VALID");
        assertThatThrownBy(() -> service.save("2026-10", 1L, new Batch(List.of(input(20, 10, "2"), input(21, 11, "2")))))
            .isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void confirmedUsagePreservesOriginalAttendanceAndAudit() throws Exception {
        service.save("2026-10", 1L, new Batch(List.of(input(20, 10, "1"))));
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_stts_cd) VALUES(30,'B-ATTEND',1,'2026-10','CONFIRM')");
        sql("INSERT INTO bill.bill_dtl(bill_id,asgn_id,work_ym,bill_amt) VALUES(30,20,'2026-10',0)");
        String before = snapshot();
        assertThatThrownBy(() -> service.save("2026-10", 1L, new Batch(List.of(input(20, 10, "2")))))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("ATTENDANCE_BILLED"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void closedMonthPreservesOriginalAttendanceAndAudit() throws Exception {
        service.save("2026-10", 1L, new Batch(List.of(input(20, 10, "1"))));
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED')");
        String before = snapshot();
        assertThatThrownBy(() -> service.save("2026-10", 1L, new Batch(List.of(input(20, 10, "2")))))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void cancelledAndOutsideMonthAssignmentsAreNotListed() throws Exception {
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=20");
        sql("UPDATE asgn.asgn SET end_dt='2026-09-30' WHERE asgn_id=21");
        assertThat(service.get("2026-10", 1L).items()).isEmpty();
    }
}
