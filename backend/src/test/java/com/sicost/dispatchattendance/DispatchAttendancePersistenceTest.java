package com.sicost.dispatchattendance;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.billing.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import com.sicost.shared.SharedReadMapper;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

class DispatchAttendancePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    DispatchAttendanceService service;
    BillingMutationService mutations;
    TransactionTemplate transaction;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/dispatchattendance/DispatchAttendanceMapper.xml"),
            new ClassPathResource("mappers/common/BillingMutationMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"),
            new ClassPathResource("mappers/shared/SharedReadMapper.xml"));
        factory.afterPropertiesSet(); var template=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,DispatchAttendanceService.class,BillingMutationService.class,
            AssignmentConcurrencyService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(DispatchAttendanceMapper.class,()->template.getMapper(DispatchAttendanceMapper.class));
        context.registerBean(BillingMutationMapper.class,()->template.getMapper(BillingMutationMapper.class));
        context.registerBean(AssignmentConcurrencyMapper.class,()->template.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(SharedReadMapper.class,()->template.getMapper(SharedReadMapper.class));
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneOffset.UTC));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
        context.refresh(); service=context.getBean(DispatchAttendanceService.class);
        mutations=context.getBean(BillingMutationService.class);
        transaction=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(1,'P-2698','근태 검증','DP','DAY')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(2,'K-ATND','근태 인력','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,untprc_base_cd) VALUES(3,'A-ATND',1,2,'DEV','2026-10-05','2026-12-31','DAY')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty,auto_mm_qty) VALUES(3,'2026-10',0.80,0.80)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close(); if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);} }
    String value(String text) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}
    }
    Input input(int days) { return new Input(3,2,BigDecimal.valueOf(days),null,null,BigDecimal.valueOf(6)); }
    Result save(int days) { return service.saveDispatchAttendance("2026-10",1,List.of(input(days))); }
    String sources() throws Exception {
        return value("SELECT json_build_object('asgn',(SELECT json_agg(a) FROM asgn.asgn a),'mm',(SELECT json_agg(m) FROM asgn.asgn_mth m),'price',(SELECT json_agg(h) FROM asgn.untprc_hist h))::text");
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('attendance',(SELECT json_agg(t ORDER BY atnd_id) FROM bill.dspch_atnd t),'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    @Test void naturalKeyUpsertReadbackAndAuditPreserveAllSourceRows() throws Exception {
        String before=sources(); var first=save(1);
        assertThat(first.items().get(0).calculated_mm()).isEqualByComparingTo("0.17");
        assertThat(first.items().get(0).price_available()).isFalse();
        String id=value("SELECT atnd_id FROM bill.dspch_atnd"); save(2);
        assertThat(value("SELECT atnd_id FROM bill.dspch_atnd")).isEqualTo(id);
        assertThat(service.getDispatchAttendance("2026-10",1).items().get(0).work_day_qty()).isEqualByComparingTo("2.0");
        assertThat(value("SELECT count(*) FROM bill.dspch_atnd")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE chg_user_id IS NULL")).isEqualTo("2");
        assertThat(value("SELECT bfr_val FROM sys.data_chg_hist ORDER BY data_chg_hist_id DESC LIMIT 1")).contains("1.0");
        assertThat(sources()).isEqualTo(before);
    }
    @Test void auditFailureRollsBackInsertAndUpdate() throws Exception {
        String before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->save(1)).isInstanceOf(RuntimeException.class); assertThat(snapshot()).isEqualTo(before);
        sql("ALTER TABLE sys.data_chg_hist DROP CONSTRAINT reject_history"); save(1); before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->save(2)).isInstanceOf(RuntimeException.class); assertThat(snapshot()).isEqualTo(before);
    }
    @Test void confirmedConnectionAndClosedMonthPreserveAttendanceAndHistory() throws Exception {
        save(1); String before=snapshot();
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_stts_cd) VALUES(10,'B-2610-10',1,'2026-10','CONFIRM')");
        sql("INSERT INTO bill.bill_dtl(bill_id,asgn_id,work_ym,bill_amt) VALUES(10,3,'2026-10',100)");
        assertThatThrownBy(()->save(2)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ATTENDANCE_BILLED"));
        assertThat(snapshot()).isEqualTo(before);
        sql("UPDATE bill.bill SET bill_stts_cd='DRAFT' WHERE bill_id=10");
        sql("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");
        assertThatThrownBy(()->save(2)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void latestPriceAtMonthlyAssignmentStartAndFreeSupport() throws Exception {
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(3,'2026-10-05',100),(3,'2026-10-20',NULL)");
        assertThat(service.getDispatchAttendance("2026-10",1).items().get(0).price_available()).isTrue();
        sql("DELETE FROM asgn.untprc_hist WHERE asgn_id=3");
        sql("UPDATE asgn.asgn SET asgn_se_cd='FREE' WHERE asgn_id=3");
        assertThat(service.getDispatchAttendance("2026-10",1).items().get(0).price_available()).isTrue();
    }
    @Test void cancelledAndOutOfMonthAssignmentsAreExcluded() throws Exception {
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id=3");
        assertThat(service.getDispatchAttendance("2026-10",1).items()).isEmpty();
        sql("UPDATE asgn.asgn SET asgn_stts_cd='ACTIVE',bgn_dt='2026-11-01' WHERE asgn_id=3");
        assertThat(service.getDispatchAttendance("2026-10",1).items()).isEmpty();
    }
    @Test void projectLockSerializesConfirmationAndAttendanceRechecksAfterLock() throws Exception {
        save(1); var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var confirm=executor.submit(()->transaction.execute(status->{
                mutations.lockProject(1); locked.countDown();
                try {
                    if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("lock timeout");
                    var c=DataSourceUtils.getConnection(source);
                    try(var s=c.createStatement()) {
                        s.execute("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_stts_cd) VALUES(10,'B-2610-10',1,'2026-10','CONFIRM')");
                        s.execute("INSERT INTO bill.bill_dtl(bill_id,asgn_id,work_ym,bill_amt) VALUES(10,3,'2026-10',100)");
                    }
                } catch(Exception e) {throw new IllegalStateException(e);}
                return true;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var save=executor.submit(()->{try {save(2);return "saved";}catch(ApiException e){return e.code();}});
            release.countDown(); confirm.get(10,TimeUnit.SECONDS);
            assertThat(save.get(10,TimeUnit.SECONDS)).isEqualTo("ATTENDANCE_BILLED");
            assertThat(value("SELECT work_day_qty FROM bill.dspch_atnd")).isEqualTo("1.0");
        } finally {release.countDown();executor.shutdownNow();}
    }
}
