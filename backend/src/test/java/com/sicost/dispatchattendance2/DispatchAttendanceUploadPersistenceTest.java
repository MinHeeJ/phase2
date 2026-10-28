package com.sicost.dispatchattendance2;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import com.sicost.uploadsops.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

class DispatchAttendanceUploadPersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    DispatchAttendanceUploadService service;
    DispatchAttendanceUploadMapper mapper;
    MockMvc mvc;
    ObjectMapper json;
    @TempDir Path directory;
    @BeforeEach void isolatedDatabase() throws Exception {
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
        factory.setMapperLocations(new ClassPathResource("mappers/dispatchattendance2/DispatchAttendanceUploadMapper.xml"),
            new ClassPathResource("mappers/uploads/UploadMapper.xml"));
        factory.afterPropertiesSet(); var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(DispatchAttendanceUploadMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        json = new ObjectMapper().findAndRegisterModules();
        service = new DispatchAttendanceUploadService(mapper, new DispatchAttendanceUploadForm(), new UploadWorkbookWriter(),
            new UploadInspectionContexts(clock), clock, new DataSourceTransactionManager(source));
        ReflectionTestUtils.setField(service, "json", json);
        ReflectionTestUtils.setField(service, "uploads", session.getMapper(UploadMapper.class));
        ReflectionTestUtils.setField(service, "directory", directory.toString());
        mvc = MockMvcBuilders.standaloneSetup(new DispatchAttendanceUploadController(service, new PermissionService(), json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(11,'P-2611','검사 프로젝트','DP','DAY'),(12,'P-2612','다른 프로젝트','DP','DAY')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(22,'K-0101','검사 인력','FT'),(23,'K-0102','제외 인력','FT'),(24,'K-0103','다른 인력','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,untprc_base_cd) VALUES(99,'A-0099',11,22,'BE','2026-01-01','2026-12-31','DAY'),(100,'A-0100',11,23,'BE','2026-01-01','2026-12-31','DAY'),(101,'A-0101',12,24,'BE','2026-01-01','2026-12-31','DAY')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(99,'2026-10',0.95),(100,'2026-10',1),(101,'2026-10',1)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(99,'2026-01-01',100000),(100,'2026-01-01',110000),(101,'2026-01-01',120000)");
        sql("INSERT INTO bill.dspch_atnd(work_ym,prj_id,dev_id,asgn_id,work_day_qty,mth_work_day_qty) VALUES('2026-10',11,22,99,1,20),('2026-10',11,23,100,2,20),('2026-10',12,24,101,3,20),('2026-09',11,22,99,4,20)");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,sply_amt,bill_stts_cd) VALUES(55,'B-DRAFT-TEST',11,'2026-10',1000,'DRAFT'),(56,'B-CONFIRM-TEST',11,'2026-09',2000,'CONFIRM')");
        sql("INSERT INTO bill.bill_dtl(bill_id,asgn_id,work_ym,mm_qty,bill_amt) VALUES(55,99,'2026-10',0.95,1000),(56,99,'2026-09',1,2000)");
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
            assertThat(r.next()).isTrue(); return r.getString(1);
        }
    }
    String protectedSources() throws Exception {
        return value("SELECT json_build_array((SELECT json_agg(m ORDER BY asgn_id) FROM asgn.asgn_mth m),(SELECT json_agg(h ORDER BY asgn_id) FROM asgn.untprc_hist h),(SELECT json_agg(d ORDER BY bill_dtl_id) FROM bill.bill_dtl d))::text");
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_array((SELECT json_agg(a ORDER BY atnd_id) FROM bill.dspch_atnd a),(SELECT json_agg(u ORDER BY upld_id) FROM sys.upld u),(SELECT json_agg(e ORDER BY upld_err_id) FROM sys.upld_err e),(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),(SELECT json_agg(h ORDER BY acs_hist_id) FROM sys.acs_hist h))::text");
    }
    long inspect() throws Exception {
        return service.validate(DispatchAttendanceUploadApiTest.SCOPE, new MockMultipartFile("file", "근태.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            DispatchAttendanceUploadApiTest.workbook(List.of(DispatchAttendanceUploadApiTest.row())))).upload_id();
    }
    @Test void anonymousInspectionThenApplyReplacesOnlyScopeAndPreservesDraftAndConfirmedSourceSnapshots() throws Exception {
        String sources = protectedSources(), before = snapshot(); long id = inspect();
        assertThat(snapshot()).isEqualTo(before);
        mvc.perform(post("/api/uploads/dispatch-attendance/apply").contentType("application/json")
            .content(json.writeValueAsString(new ApplyInput(id, true))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.applied_count").value(1));
        assertThat(value("SELECT count(*) FROM bill.dspch_atnd WHERE prj_id=11 AND work_ym='2026-10'")).isEqualTo("1");
        assertThat(value("SELECT work_day_qty FROM bill.dspch_atnd WHERE prj_id=11 AND work_ym='2026-10'")).isEqualTo("20.0");
        assertThat(value("SELECT count(*) FROM bill.dspch_atnd WHERE prj_id=12 OR work_ym='2026-09'")).isEqualTo("2");
        assertThat(value("SELECT upld_stts_cd FROM sys.upld WHERE upld_id=" + id)).isEqualTo("APPLIED");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.dspch_atnd'")).isEqualTo("3");
        assertThat(protectedSources()).isEqualTo(sources);
        assertThat(mapper.attendance(DispatchAttendanceUploadApiTest.SCOPE).get(0).dev_id()).isEqualTo(22);
    }
    @ParameterizedTest @ValueSource(strings = {"bill.dspch_atnd", "sys.upld", "sys.upld_err", "sys.data_chg_hist", "sys.acs_hist"})
    void anyAttendanceOrMetadataFailureRollsBackEntireScope(String table) throws Exception {
        long id = inspect(); String before = snapshot(), sources = protectedSources();
        sql("ALTER TABLE " + table + " ADD CONSTRAINT reject_attendance_write CHECK(false) NOT VALID");
        assertThatThrownBy(() -> service.apply(new ApplyInput(id, true))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before); assertThat(protectedSources()).isEqualTo(sources);
    }
    @Test void confirmationAddedAfterValidationBlocksEntireScopeIncludingOmittedRows() throws Exception {
        long id = inspect();
        sql("INSERT INTO bill.bill_dtl(bill_id,asgn_id,work_ym,bill_amt) VALUES(56,100,'2026-10',1)");
        String before = snapshot();
        assertThatThrownBy(() -> service.apply(new ApplyInput(id, true))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo("ATTENDANCE_BILLED"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void closeAddedAfterValidationPreservesOriginalAttendance() throws Exception {
        long id = inspect(); sql("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");
        String before = snapshot();
        assertThatThrownBy(() -> service.apply(new ApplyInput(id, true))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void changedAttendanceAfterValidationCannotBeOverwrittenByStaleReview() throws Exception {
        long id = inspect(); sql("UPDATE bill.dspch_atnd SET work_day_qty=9 WHERE prj_id=11 AND work_ym='2026-10'");
        String before = snapshot();
        assertThatThrownBy(() -> service.apply(new ApplyInput(id, true))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo("STALE_VALIDATION"));
        assertThat(snapshot()).isEqualTo(before);
    }
}
