package com.sicost.dispatchattendance2;

import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.persistence.*;
import com.sicost.common.security.CurrentUser;
import com.sicost.uploadsops.*;
import java.nio.file.*;
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

class DispatchAttendanceUploadPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    DispatchAttendanceUploadService service;
    DispatchAttendanceUploadMapper mapper;
    @TempDir Path directory;
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/uploads/UploadMapper.xml"),
            new ClassPathResource("mappers/uploads/PersonnelUploadMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"),
            new ClassPathResource("mappers/dispatchattendance2/DispatchAttendanceUploadMapper.xml"));
        factory.afterPropertiesSet();
        var session=new SqlSessionTemplate(factory.getObject());
        mapper=session.getMapper(DispatchAttendanceUploadMapper.class);
        var legacy=session.getMapper(UploadMapper.class);
        var manager=new DataSourceTransactionManager(source);
        Clock clock=Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));
        var actor=new CurrentUser();var writer=new UploadWorkbookWriter();
        var storage=new UploadService(legacy,actor,clock,writer,new PartnerUploadForm(legacy),manager);
        ReflectionTestUtils.setField(storage,"directory",directory.toString());
        service=new DispatchAttendanceUploadService(mapper,new DispatchAttendanceUploadForm(mapper),storage,writer,clock,manager);
        ReflectionTestUtils.setField(service,"allocation",session.getMapper(PersonnelUploadMapper.class));
        ReflectionTestUtils.setField(service,"uploads",legacy);
        ReflectionTestUtils.setField(service,"user",actor);
        ReflectionTestUtils.setField(service,"json",new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(service,"inspections",new UploadInspectionContexts(clock));
        ReflectionTestUtils.setField(service,"concurrency",new AssignmentConcurrencyService(session.getMapper(AssignmentConcurrencyMapper.class),actor));
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(1,'P-ATTEND','근태 시험','DP','DAY'),(2,'P-OTHER','다른 범위','DP','DAY')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(21,'K-0021','검사 인력','FT'),(22,'K-0022','제외 인력','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,untprc_base_cd) VALUES(11,'A-ATTEND',1,21,'BE','2026-01-01','2026-12-31','DAY'),(12,'A-OTHER',1,22,'BE','2026-01-01','2026-12-31','DAY')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(11,'2026-10',0.8)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt,pay_untprc_amt,ovtm_untprc_amt) VALUES(11,'2026-01-01',300000,123456,98765)");
        sql("INSERT INTO bill.dspch_atnd(work_ym,prj_id,dev_id,asgn_id,work_day_qty,mth_work_day_qty) VALUES('2026-10',1,21,11,5,20),('2026-10',1,22,12,7,20),('2026-09',1,21,11,8,20),('2026-10',2,21,NULL,9,20)");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_stts_cd,sply_amt) VALUES(100,'B-DRAFT',1,'2026-10','DRAFT',1500000),(101,'B-CONFIRM',1,'2026-09','CONFIRM',2400000)");
        sql("INSERT INTO bill.bill_dtl(bill_id,asgn_id,work_ym,mm_qty,untprc_amt,work_day_qty,bill_amt) VALUES(100,11,'2026-10',0.25,300000,5,1500000),(101,11,'2026-09',0.4,300000,8,2400000)");
    }
    @AfterEach void cleanup() throws Exception {
        if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String value) throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(value);}}
    String value(String query) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(query)) {assertThat(r.next()).isTrue();return r.getString(1);}
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_array((SELECT json_agg(a ORDER BY atnd_id) FROM bill.dspch_atnd a),(SELECT json_agg(d ORDER BY bill_dtl_id) FROM bill.bill_dtl d),(SELECT json_agg(a ORDER BY asgn_id,work_ym) FROM asgn.asgn_mth a),(SELECT json_agg(h ORDER BY asgn_id,aply_bgn_dt) FROM asgn.untprc_hist h),(SELECT json_agg(u ORDER BY upld_id) FROM sys.upld u),(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),(SELECT json_agg(a ORDER BY acs_hist_id) FROM sys.acs_hist a))::text");
    }
    long inspect() throws Exception {
        return service.validate(new MockMultipartFile("file","근태.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            DispatchAttendanceUploadApiTest.workbook(List.of(DispatchAttendanceUploadApiTest.row()))),"2026-10",1).upload_id();
    }
    @Test void validateIsReadOnlyAndApplyReplacesFullScopePreservingDraftConfirmedAndSources() throws Exception {
        String before=snapshot();long id=inspect();assertThat(snapshot()).isEqualTo(before);
        String drafts=value("SELECT json_agg(d ORDER BY bill_dtl_id)::text FROM bill.bill_dtl d");
        String sources=value("SELECT json_agg(a ORDER BY asgn_id,work_ym)::text FROM asgn.asgn_mth a");
        String rates=value("SELECT json_agg(h ORDER BY asgn_id,aply_bgn_dt)::text FROM asgn.untprc_hist h");
        assertThat(service.apply(new Apply(id,true)).applied_count()).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM bill.dspch_atnd WHERE prj_id=1 AND work_ym='2026-10'")).isEqualTo("1");
        assertThat(value("SELECT work_day_qty FROM bill.dspch_atnd WHERE prj_id=1 AND work_ym='2026-10'")).isEqualTo("20.0");
        assertThat(value("SELECT count(*) FROM bill.dspch_atnd WHERE prj_id=2 OR work_ym='2026-09'")).isEqualTo("2");
        assertThat(value("SELECT json_agg(d ORDER BY bill_dtl_id)::text FROM bill.bill_dtl d")).isEqualTo(drafts);
        assertThat(value("SELECT json_agg(a ORDER BY asgn_id,work_ym)::text FROM asgn.asgn_mth a")).isEqualTo(sources);
        assertThat(value("SELECT json_agg(h ORDER BY asgn_id,aply_bgn_dt)::text FROM asgn.untprc_hist h")).isEqualTo(rates);
        assertThat(value("SELECT count(*) FROM sys.upld WHERE upld_id="+id+" AND upld_stts_cd='APPLIED' AND form_cd='B.3'")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.dspch_atnd' AND bfr_val IS NOT NULL")).isEqualTo("2");
    }
    @ParameterizedTest @ValueSource(strings={"bill.dspch_atnd","sys.upld","sys.data_chg_hist","sys.acs_hist"})
    void everyStorageFailureRollsBackWholeRange(String table) throws Exception {
        long id=inspect();String before=snapshot();
        String predicate=switch(table) {
            case "bill.dspch_atnd" -> "work_day_qty != 20";
            case "sys.upld" -> "form_cd != 'B.3'";
            case "sys.data_chg_hist" -> "tbl_nm != 'bill.dspch_atnd'";
            default -> "scrn_id != 'UI-13'";
        };
        sql("ALTER TABLE "+table+" ADD CONSTRAINT reject_attendance_write CHECK ("+predicate+") NOT VALID");
        assertThatThrownBy(()->service.apply(new Apply(id,true))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);assertThat(service.result(id).can_apply()).isTrue();
    }
    @Test void lateConfirmedLinkRefusesWholeReplacement() throws Exception {
        long id=inspect();sql("UPDATE bill.bill SET bill_stts_cd='CONFIRM' WHERE bill_id=100");String before=snapshot();
        assertThatThrownBy(()->service.apply(new Apply(id,true))).isInstanceOfSatisfying(ApiException.class,
            e->assertThat(e.code()).isEqualTo("ATTENDANCE_BILLED"));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void lateCloseRefusesWholeReplacement() throws Exception {
        long id=inspect();sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");String before=snapshot();
        assertThatThrownBy(()->service.apply(new Apply(id,true))).isInstanceOfSatisfying(ApiException.class,
            e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void changedSourceFileRequiresReinspectionBeforeAnyWrite() throws Exception {
        long id=inspect();String before=snapshot();
        try(var paths=Files.walk(directory)) {
            Path original=paths.filter(Files::isRegularFile).findFirst().orElseThrow();
            Files.write(original,"changed".getBytes());
        }
        assertThatThrownBy(()->service.apply(new Apply(id,true))).isInstanceOfSatisfying(ApiException.class,
            e->assertThat(e.code()).isEqualTo("STALE_VALIDATION"));assertThat(snapshot()).isEqualTo(before);
    }
}
