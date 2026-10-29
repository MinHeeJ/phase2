package com.sicost.dispatchattendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

class DispatchAttendancePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    DispatchAttendanceService service;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL integration requires TEST_DATABASE_URL");
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
        factory.afterPropertiesSet();
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(DispatchAttendanceMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, DispatchAttendanceService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(DispatchAttendanceMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class, () -> json); context.refresh();
        service = context.getBean(DispatchAttendanceService.class);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(1,'P-2601','파견','DP','DAY'),(2,'P-2602','타 프로젝트','DP','HOUR')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'K-0001','인력','FT'),(2,'K-0002','타 인력','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,untprc_base_cd) VALUES(1,'A-0001',1,1,'DEV','2026-01-01','2026-12-31','DAY'),(2,'A-0002',2,2,'DEV','2026-01-01','2026-12-31','HOUR')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(1,'2026-10',0.75),(2,'2026-10',0.50)");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context != null) context.close();
        if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    void sql(String query) throws Exception { try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(query); } }
    String value(String query) { return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(query, String.class); }
    Scope scope() { return DispatchAttendanceInput.scope("2026-10", "1"); }
    List<AttendanceInput> input(String days) throws Exception {
        return DispatchAttendanceInput.parse(json.readTree("{\"items\":[{\"asgn_id\":1,\"dev_id\":1,\"mth_work_day_qty\":8,\"work_day_qty\":" + days + "}]}"));
    }
    String snapshot() {
        return value("SELECT json_build_object('attendance',(SELECT json_agg(t ORDER BY atnd_id) FROM bill.dspch_atnd t),'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    @Test void saveReloadAndUpdateKeepsNaturalIdentityAndSourceMm() throws Exception {
        var first = service.save(scope(), input("1"));
        assertThat(first.items().get(0).calculated_mm()).isEqualByComparingTo("0.13");
        assertThat(first.items().get(0).price_available()).isFalse();
        String id = value("SELECT atnd_id FROM bill.dspch_atnd WHERE prj_id=1");
        service.save(scope(), input("0"));
        assertThat(value("SELECT atnd_id FROM bill.dspch_atnd WHERE prj_id=1")).isEqualTo(id);
        assertThat(service.get(scope()).items().get(0).work_day_qty()).isEqualByComparingTo("0");
        assertThat(value("SELECT mm_qty FROM asgn.asgn_mth WHERE asgn_id=1")).isEqualTo("0.75");
        assertThat(value("SELECT count(*) FROM asgn.untprc_hist")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.dspch_atnd' AND chg_user_id IS NULL")).isEqualTo("2");
        assertThat(value("SELECT bfr_val::jsonb->>'work_day_qty' FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).isEqualTo("1.0");
    }
    @Test void auditFailureRollsBackEntireCommand() throws Exception {
        service.save(scope(), input("1")); String before = snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(() -> service.save(scope(), input("0"))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void closedMonthPreservesAttendanceAndHistory() throws Exception {
        service.save(scope(), input("1")); String before = snapshot();
        sql("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");
        assertThatThrownBy(() -> service.save(scope(), input("0"))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void confirmedDetailProtectsAttendanceWithoutChangingSnapshot() throws Exception {
        service.save(scope(), input("1"));
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_stts_cd) VALUES(1,'B-2610-01',1,'2026-10','CONFIRM')");
        sql("INSERT INTO bill.bill_dtl(bill_id,asgn_id,work_ym,bill_amt) VALUES(1,1,'2026-10',100)");
        String before = snapshot();
        assertThatThrownBy(() -> service.save(scope(), input("0"))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo("ATTENDANCE_BILLED"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void overlappingAssignmentsRejectAmbiguousPersonAndCancelledAreExcluded() throws Exception {
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,untprc_base_cd) VALUES(3,'A-0003',1,1,'DEV','2026-10-15','2026-10-31','DAY')");
        assertThatThrownBy(() -> service.save(scope(), input("1"))).isInstanceOf(ApiException.class);
        assertThat(value("SELECT count(*) FROM bill.dspch_atnd")).isEqualTo("0");
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=3");
        assertThat(service.save(scope(), input("1")).items()).hasSize(1);
    }
    @Test void hourOptionalDayStaysNullAndOtherProjectIsNotWritten() throws Exception {
        var inputs = DispatchAttendanceInput.parse(json.readTree("{\"items\":[{\"asgn_id\":2,\"dev_id\":2,\"mth_work_day_qty\":20,\"rglr_tm_qty\":0}]}"));
        var result = service.save(DispatchAttendanceInput.scope("2026-10", "2"), inputs);
        assertThat(result.items().get(0).calculated_mm()).isNull();
        assertThat(value("SELECT count(*) FROM bill.dspch_atnd WHERE prj_id=1")).isEqualTo("0");
    }
}
