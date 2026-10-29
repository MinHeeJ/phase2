package com.sicost.dispatchattendance2;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.billing.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import com.sicost.shared.SharedReadMapper;
import com.sicost.uploadsops.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;

class DispatchAttendanceUploadPersistenceTest {
    String adminUrl,user,password,database;DriverManagerDataSource source;
    DispatchAttendanceUploadService service;DispatchAttendanceUploadMapper mapper;
    @TempDir Path directory;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        String[] paths={"mappers/dispatchattendance2/DispatchAttendanceUploadMapper.xml","mappers/uploads/UploadMapper.xml","mappers/uploads/PersonnelUploadMapper.xml","mappers/common/BillingMutationMapper.xml","mappers/common/AssignmentConcurrencyMapper.xml","mappers/shared/SharedReadMapper.xml"};
        factory.setMapperLocations(Arrays.stream(paths).map(ClassPathResource::new).toArray(ClassPathResource[]::new));factory.afterPropertiesSet();
        var session=new SqlSessionTemplate(factory.getObject());mapper=session.getMapper(DispatchAttendanceUploadMapper.class);
        var current=new CurrentUser();Clock clock=Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));
        var mutation=new BillingMutationService(session.getMapper(BillingMutationMapper.class),new AssignmentConcurrencyService(session.getMapper(AssignmentConcurrencyMapper.class),current),session.getMapper(SharedReadMapper.class),current,clock);
        service=new DispatchAttendanceUploadService(mapper,new DispatchAttendanceUploadForm(),session.getMapper(PersonnelUploadMapper.class),session.getMapper(UploadMapper.class),new UploadInspectionContexts(clock),new UploadWorkbookWriter(),mutation,current,new PermissionService(),clock,new ObjectMapper().findAndRegisterModules(),new DataSourceTransactionManager(source));
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(1,'P-2601','근태 시험','DP','DAY')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'DEV-1','홍길동','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,untprc_base_cd) VALUES(1,'A-2601',1,1,'DEV','2026-01-01','2026-12-31','DAY')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty,auto_mm_qty) VALUES(1,'2026-10',0.75,0.75)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(1,'2026-01-01',100000)");
        sql("INSERT INTO bill.dspch_atnd(work_ym,prj_id,dev_id,asgn_id,work_day_qty,mth_work_day_qty) VALUES('2026-10',1,1,1,5,20)");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,sply_amt) VALUES(1,'B-2610-01',1,'2026-10',500000)");
        sql("INSERT INTO bill.bill_dtl(bill_id,asgn_id,work_ym,mm_qty,bill_amt) VALUES(1,1,'2026-10',0.75,500000)");
    }
    @AfterEach void removeDatabase()throws Exception {if(source==null)return;try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s,database);}}
    void sql(String text)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);}}
    String value(String text)throws Exception{try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}}
    Path workbook()throws Exception{Path path=directory.resolve("근태.xlsx");try(var book=new XSSFWorkbook();var out=Files.newOutputStream(path)){var s=book.createSheet();var h=s.createRow(3);for(int c=0;c<8;c++)h.createCell(c).setCellValue(DispatchAttendanceUploadForm.HEADERS.get(c));var row=s.createRow(5);String[] v={"2026-10","1","DEV-1","홍길동","10","80","","20"};for(int c=0;c<8;c++)row.createCell(c).setCellValue(v[c]);book.write(out);}return path;}
    long inspect()throws Exception{return service.validate("2026-10",1,"근태.xlsx",workbook()).upload_id();}
    String snapshot()throws Exception{return value("SELECT json_build_object('attendance',(SELECT json_agg(row_to_json(a) ORDER BY atnd_id) FROM bill.dspch_atnd a),'upload',(SELECT json_agg(row_to_json(u)) FROM sys.upld u),'history',(SELECT json_agg(row_to_json(h)) FROM sys.data_chg_hist h))::text");}
    @Test void validateReadOnlyThenApplyPersistsWholeScopeAndPreservesDraftAndSource()throws Exception {
        String before=snapshot();long id=inspect();assertThat(snapshot()).isEqualTo(before);
        assertThat(service.apply(id).applied_count()).isEqualTo(1);
        assertThat(value("SELECT work_day_qty FROM bill.dspch_atnd WHERE prj_id=1")).isEqualTo("10.0");
        assertThat(value("SELECT upld_id FROM bill.dspch_atnd WHERE prj_id=1")).isEqualTo(Long.toString(id));
        assertThat(value("SELECT mm_qty FROM asgn.asgn_mth WHERE asgn_id=1")).isEqualTo("0.75");
        assertThat(value("SELECT bill_untprc_amt FROM asgn.untprc_hist WHERE asgn_id=1")).isEqualTo("100000");
        assertThat(value("SELECT bill_amt FROM bill.bill_dtl WHERE bill_id=1")).isEqualTo("500000");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.dspch_atnd'")).isEqualTo("2");
        assertThat(value("SELECT count(*) FROM sys.acs_hist WHERE scrn_id='UI-13' AND acs_ty_cd='UPLOAD'")).isEqualTo("1");
    }
    @ParameterizedTest @ValueSource(strings={"sys.upld","bill.dspch_atnd","sys.data_chg_hist","sys.acs_hist"})
    void anyWriteFailureRollsBackEntireScope(String table)throws Exception {
        long id=inspect();String before=snapshot();
        sql("CREATE FUNCTION public.reject_attendance_write() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''write rejected''; END'");
        sql("CREATE TRIGGER reject_attendance_write BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION public.reject_attendance_write()");
        assertThatThrownBy(()->service.apply(id)).isInstanceOf(RuntimeException.class);assertThat(snapshot()).isEqualTo(before);
        sql("DROP TRIGGER reject_attendance_write ON "+table);assertThat(service.apply(id).applied_count()).isEqualTo(1);
    }
    @Test void newlyConfirmedLinkRejectsAndPreservesSnapshot()throws Exception {
        long id=inspect();sql("UPDATE bill.bill SET bill_stts_cd='CONFIRM' WHERE bill_id=1");String before=snapshot();
        assertThatThrownBy(()->service.apply(id)).isInstanceOf(com.sicost.common.api.ApiException.class).hasMessageContaining("확정");assertThat(snapshot()).isEqualTo(before);
    }
}
