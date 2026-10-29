package com.sicost.dispatchattendance2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.security.*;
import com.sicost.uploadsops.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

class DispatchAttendanceUploadPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    DispatchAttendanceUploadService service;
    DispatchAttendanceUploadMapper mapper;
    UploadMapper common;
    JdbcTemplate jdbc;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @TempDir Path directory;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try (Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/dispatchattendance2/DispatchAttendanceUploadMapper.xml"),
            new ClassPathResource("mappers/uploads/UploadMapper.xml"));factory.afterPropertiesSet();
        var session=new SqlSessionTemplate(factory.getObject());
        mapper=session.getMapper(DispatchAttendanceUploadMapper.class);common=session.getMapper(UploadMapper.class);
        jdbc=new JdbcTemplate(source);startContext();
        jdbc.execute("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(1,'P-ATND-1','파견','DP','DAY'),(2,'P-ATND-2','다른 파견','DP','DAY')");
        jdbc.execute("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(21,'D-100','홍길동','FT'),(22,'D-200','다른 인력','FT')");
        jdbc.execute("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,untprc_base_cd) VALUES(11,'A-ATND-11',1,21,'BE','2026-10-01','2026-11-30','DAY'),(12,'A-ATND-12',1,22,'BE','2026-10-01','2026-11-30','DAY')");
        jdbc.execute("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(11,'2026-10',0.5)");
        jdbc.execute("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(11,'2026-10-01',100000)");
        jdbc.execute("INSERT INTO bill.dspch_atnd(work_ym,prj_id,dev_id,asgn_id,work_day_qty,mth_work_day_qty) VALUES('2026-10',1,22,12,15,20),('2026-11',1,22,12,16,20),('2026-10',2,22,NULL,17,20)");
        jdbc.execute("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_stts_cd) VALUES(101,'B-ATND-101',1,'2026-10','DRAFT')");
        jdbc.execute("INSERT INTO bill.bill_dtl(bill_id,asgn_id,cntrct_dev_id,work_ym,mm_qty,bill_amt) VALUES(101,11,21,'2026-10',0.8,123456)");
    }
    void startContext() {
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,DispatchAttendanceUploadService.class,DispatchAttendanceUploadForm.class,
            UploadWorkbookWriter.class,CurrentUser.class,PermissionService.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("attendance",java.util.Map.of("sicost.upload-directory",directory.toString())));
        context.registerBean(DispatchAttendanceUploadMapper.class,()->mapper);
        context.registerBean(UploadMapper.class,()->common);
        context.registerBean(ObjectMapper.class,()->json);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(UploadService.class,()->{
            var uploads=new UploadService(common,new CurrentUser(),Clock.systemUTC(),new UploadWorkbookWriter(),mock(PartnerUploadForm.class),new DataSourceTransactionManager(source));
            ReflectionTestUtils.setField(uploads,"directory",directory.toString());return uploads;
        });
        context.refresh();service=context.getBean(DispatchAttendanceUploadService.class);
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context!=null) context.close();if(source==null)return;
        try (Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    long review() throws Exception { return service.validate("2026-10",1,AttendanceWorkbooks.file(AttendanceWorkbooks.values())).upload_id(); }
    String value(String sql) { return jdbc.queryForObject(sql,String.class); }
    String snapshot() {
        return value("SELECT json_build_object('attendance',(SELECT json_agg(t ORDER BY t.atnd_id) FROM bill.dspch_atnd t),'upload',(SELECT json_agg(u ORDER BY u.upld_id) FROM sys.upld u),'audit',(SELECT json_agg(h ORDER BY h.data_chg_hist_id) FROM sys.data_chg_hist h),'access',(SELECT json_agg(a ORDER BY a.acs_hist_id) FROM sys.acs_hist a))::text");
    }
    String protectedRows() {
        return value("SELECT json_build_object('mm',(SELECT json_agg(m ORDER BY m.asgn_id,m.work_ym) FROM asgn.asgn_mth m),'prices',(SELECT json_agg(p ORDER BY p.asgn_id,p.aply_bgn_dt) FROM asgn.untprc_hist p),'details',(SELECT json_agg(d ORDER BY d.bill_dtl_id) FROM bill.bill_dtl d))::text");
    }
    @Test void durableReviewSurvivesRestartAndAtomicallyReplacesOnlyScopeWithoutRecalculatingDraft() throws Exception {
        String protectedBefore=protectedRows();long id=review();
        assertThat(value("SELECT count(*) FROM sys.upld WHERE form_cd='B.3'")).isEqualTo("0");
        context.close();startContext();
        assertThat(service.result(id).can_apply()).isTrue();assertThat(service.rows(id,null,new com.sicost.common.api.DeveloperPaging(0,50,null)).items().get(0).row()).isEqualTo(6);
        service.apply(id);
        assertThat(value("SELECT string_agg(dev_id::text,',' ORDER BY dev_id) FROM bill.dspch_atnd WHERE prj_id=1 AND work_ym='2026-10'")).isEqualTo("21");
        assertThat(value("SELECT work_day_qty FROM bill.dspch_atnd WHERE prj_id=1 AND work_ym='2026-11'")).isEqualTo("16.0");
        assertThat(value("SELECT work_day_qty FROM bill.dspch_atnd WHERE prj_id=2 AND work_ym='2026-10'")).isEqualTo("17.0");
        assertThat(value("SELECT upld_stts_cd FROM sys.upld WHERE upld_id="+id)).isEqualTo("APPLIED");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.dspch_atnd' AND chg_user_id IS NULL")).isEqualTo("2");
        assertThat(value("SELECT count(*) FROM sys.acs_hist WHERE scrn_id='UI-13' AND acs_ty_cd='UPLOAD'")).isEqualTo("1");
        assertThat(protectedRows()).isEqualTo(protectedBefore);
        service.apply(id);assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.dspch_atnd'")).isEqualTo("2");
    }
    @ParameterizedTest @ValueSource(strings={"bill.dspch_atnd","sys.upld","sys.data_chg_hist","sys.acs_hist"})
    void failureAtAnyPersistenceBoundaryRollsBackWholeReplacement(String table) throws Exception {
        long id=review();String before=snapshot(),protectedBefore=protectedRows();
        jdbc.execute("ALTER TABLE "+table+" ADD CONSTRAINT reject_attendance_write CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.apply(id)).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);assertThat(protectedRows()).isEqualTo(protectedBefore);
    }
    @ParameterizedTest @ValueSource(strings={"CLOSED","CONFIRM"})
    void stateChangingAfterReviewPreservesOriginalAttendance(String state) throws Exception {
        long id=review();
        if (state.equals("CLOSED")) jdbc.execute("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");
        else jdbc.execute("UPDATE bill.bill SET bill_stts_cd='CONFIRM' WHERE bill_id=101");
        String before=snapshot();
        assertThatThrownBy(()->service.apply(id)).isInstanceOfSatisfying(com.sicost.common.api.ApiException.class,e->assertThat(e.status()).isEqualTo(409));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void rejectedInspectionRecordsIssuesButLeavesBusinessAndSnapshotsUntouched() throws Exception {
        var values=AttendanceWorkbooks.values();values.put("해당 월 근무일수","");String before=protectedRows();
        var result=service.validate("2026-10",1,AttendanceWorkbooks.file(values));
        assertThat(result.can_apply()).isFalse();
        assertThat(value("SELECT upld_stts_cd FROM sys.upld WHERE upld_id="+result.upload_id())).isEqualTo("REJECTED");
        assertThat(value("SELECT row_no FROM sys.upld_err WHERE upld_id="+result.upload_id()+" LIMIT 1")).isEqualTo("6");
        assertThat(value("SELECT dev_id FROM bill.dspch_atnd WHERE prj_id=1 AND work_ym='2026-10'")).isEqualTo("22");
        assertThat(protectedRows()).isEqualTo(before);
    }
}
