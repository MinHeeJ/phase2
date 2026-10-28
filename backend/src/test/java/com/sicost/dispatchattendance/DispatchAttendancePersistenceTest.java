package com.sicost.dispatchattendance;

import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class DispatchAttendancePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    DispatchAttendanceService service;
    DispatchAttendanceMapper mapper;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1)
            + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public")
            .locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/dispatchattendance/DispatchAttendanceMapper.xml"));
        factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(DispatchAttendanceMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, DispatchAttendanceService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(DispatchAttendanceMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
            () -> new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.refresh(); service = context.getBean(DispatchAttendanceService.class);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(1,'P-ATT','근태 시험','DP','DAY'),(2,'P-HOUR','시간 시험','DP','HOUR')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'D-ATT1','근태 시험1','FT'),(2,'D-ATT2','근태 시험2','FT'),(3,'D-ATT3','근태 시험3','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,untprc_base_cd) VALUES(1,'A-ATT1',1,1,'DEV','2026-01-01','2026-12-31','DAY'),(2,'A-ATT2',1,2,'DEV','2026-01-01','2026-12-31','DAY'),(3,'A-ATT3',2,3,'DEV','2026-01-01','2026-12-31','HOUR')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(1,'2026-10',0.5),(2,'2026-10',1),(3,'2026-10',1)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(2,'2026-01-01',100000),(3,'2026-01-01',10000)");
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
    Input day(long assignment, long developer, String days) {
        return new Input(assignment, developer, new BigDecimal(days), null, null, new BigDecimal("21"));
    }
    String sources() throws Exception {
        return value("SELECT json_build_object('assignments',(SELECT json_agg(a ORDER BY asgn_id) FROM asgn.asgn a),'months',(SELECT json_agg(m ORDER BY asgn_id) FROM asgn.asgn_mth m),'rates',(SELECT json_agg(h ORDER BY asgn_id) FROM asgn.untprc_hist h))::text");
    }
    String attendance() throws Exception {
        return value("SELECT json_build_object('attendance',(SELECT json_agg(a ORDER BY atnd_id) FROM bill.dspch_atnd a),'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    @Test void manualUpsertPersistsRoundedMmComparisonAndRetainsOtherDevelopers() throws Exception {
        String before = sources();
        var first = service.saveDispatchAttendance("2026-10", 1L, List.of(day(1,1,"10"), day(2,2,"0")));
        assertThat(first.items().get(0).calculated_mm()).isEqualByComparingTo("0.48");
        assertThat(first.items().get(0).price_available()).isFalse();
        service.saveDispatchAttendance("2026-10", 1L, List.of(day(1,1,"11")));
        assertThat(value("SELECT work_day_qty FROM bill.dspch_atnd WHERE dev_id=2")).isEqualTo("0.0");
        assertThat(value("SELECT count(*) FROM bill.dspch_atnd")).isEqualTo("2");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.dspch_atnd'")).isEqualTo("3");
        assertThat(value("SELECT bfr_val FROM sys.data_chg_hist WHERE tbl_nm='bill.dspch_atnd' AND data_chg_ty_cd='UPDATE'"))
            .contains("10.0", "work_day_qty", "mth_work_day_qty");
        assertThat(sources()).isEqualTo(before);
        assertThat(service.getDispatchAttendance("2026-10",1L).items().get(0).work_day_qty())
            .isEqualByComparingTo("11");
    }
    @Test void hourWithoutDaysPersistsNullMmAndAllowsZeroHours() throws Exception {
        var input = new Input(3,3,null,BigDecimal.ZERO,null,new BigDecimal("21"));
        var saved = service.saveDispatchAttendance("2026-10",2L,List.of(input));
        assertThat(saved.items().get(0).calculated_mm()).isNull();
        assertThat(saved.items().get(0).warnings()).isNotEmpty();
        assertThat(value("SELECT work_day_qty IS NULL FROM bill.dspch_atnd")).isEqualTo("t");
        assertThat(value("SELECT rglr_tm_qty FROM bill.dspch_atnd")).isEqualTo("0.0");
    }
    @Test void auditFailureRollsBackEveryAttendanceWrite() throws Exception {
        service.saveDispatchAttendance("2026-10",1L,List.of(day(1,1,"10")));
        String before = attendance();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(() -> service.saveDispatchAttendance("2026-10",1L,
            List.of(day(1,1,"11"),day(2,2,"12")))).isInstanceOf(RuntimeException.class);
        assertThat(attendance()).isEqualTo(before);
    }
    @Test void confirmedLinksAndClosedMonthPreserveStoredRowsAndAudits() throws Exception {
        service.saveDispatchAttendance("2026-10",1L,List.of(day(1,1,"10")));
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_stts_cd) VALUES(1,'B-ATT',1,'2026-10','CONFIRM')");
        sql("INSERT INTO bill.bill_dtl(bill_id,asgn_id,work_ym,bill_amt) VALUES(1,1,'2026-10',1000)");
        String before = attendance();
        assertThatThrownBy(() -> service.saveDispatchAttendance("2026-10",1L,List.of(day(1,1,"11"))))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ATTENDANCE_BILLED"));
        assertThat(attendance()).isEqualTo(before);
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED')");
        assertThatThrownBy(() -> service.saveDispatchAttendance("2026-10",1L,List.of(day(2,2,"11"))))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(attendance()).isEqualTo(before);
    }
    @Test void listExcludesCancelledOutOfRangeWrongProjectAndMonthBasisAssignments() throws Exception {
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=1");
        sql("UPDATE asgn.asgn SET bgn_dt='2026-11-01' WHERE asgn_id=2");
        assertThat(service.getDispatchAttendance("2026-10",1L).items()).isEmpty();
        assertThat(service.getDispatchAttendance("2026-10",2L).items()).hasSize(1);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo("0");
    }
    @Test void concurrentUpsertsUseOneNaturalIdentityAndProjectLock() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return service.saveDispatchAttendance("2026-10",1L,List.of(day(1,1,"10"))); });
            var second = executor.submit(() -> { start.await(); return service.saveDispatchAttendance("2026-10",1L,List.of(day(1,1,"11"))); });
            start.countDown(); first.get(10,TimeUnit.SECONDS); second.get(10,TimeUnit.SECONDS);
            assertThat(value("SELECT count(*) FROM bill.dspch_atnd")).isEqualTo("1");
            assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='INSERT'")).isEqualTo("1");
            assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).isEqualTo("1");
        } finally { executor.shutdownNow(); }
    }
}
