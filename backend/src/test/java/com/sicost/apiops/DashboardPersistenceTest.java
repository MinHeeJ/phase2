package com.sicost.apiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.billingsops.*;
import com.sicost.common.security.PermissionService;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DashboardPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    BillingMapper billing;
    ClosingQueryMapper closing;
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mappers/**/*.xml"));factory.afterPropertiesSet();
        var session=new SqlSessionTemplate(factory.getObject());billing=session.getMapper(BillingMapper.class);closing=session.getMapper(ClosingQueryMapper.class);
    }
    @AfterEach void cleanup() throws Exception {
        if(source==null)return;
        try(var admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    void sql(String sql){new JdbcTemplate(source).execute(sql);}
    String value(String sql){return new JdbcTemplate(source).queryForObject(sql,String.class);}
    @Test void priorYearOutstandingAndFutureConfirmationsUseCapturedCutoffWithoutWrites() {
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cntrct_bgn_dt,cntrct_end_dt) VALUES(901,'P-26901','미수 조회','MO','RUN','2025-01-01','2027-12-31')");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd,rcpt_plan_dt,cnfrm_at) VALUES(901,'B-2512-91',901,'2025-12','2025-12-01',1000,100,'CONFIRM','2026-10-28','2025-12-01 12:00'),(902,'B-2611-92',901,'2026-11','2026-11-01',1000,100,'CONFIRM','2026-11-10','2026-11-01 12:00')");
        sql("INSERT INTO bill.rcpt(bill_id,rcpt_dt,rcpt_amt) VALUES(901,'2026-10-20',400),(901,'2026-11-01',100)");
        String before=value("SELECT json_agg(b)::text FROM bill.bill b");
        var clock=Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));
        var service=new BillingReceivableService(billing,clock,new PermissionService());
        var total=service.currentReceivables(clock.instant());
        assertThat(total.amount()).isEqualTo(700);assertThat(total.overdueAmount()).isEqualTo(700);assertThat(total.overdueCount()).isEqualTo(1);
        assertThat(value("SELECT json_agg(b)::text FROM bill.bill b")).isEqualTo(before);
    }
    @Test void latestImmutableCheckRunBeforeCloseWinsOverLaterRunsAndMutableChecks() throws Exception {
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd,cls_at) VALUES('2026-10','CLOSED','2026-10-28 12:00') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED',cls_at=excluded.cls_at");
        var json=new ObjectMapper();var checks=java.util.stream.IntStream.range(0,9).mapToObj(i->new ClosingQueryModels.Check("C"+i,"OK",0,"확정 당시")).toList();
        var jdbc=new JdbcTemplate(source);jdbc.update("INSERT INTO fin.mth_cls_chk_hist(cls_ym,chk_rslt_json,chk_at) VALUES(?,?::jsonb,?::timestamp)","2026-10",json.writeValueAsString(checks),"2026-10-28 11:00");
        jdbc.update("INSERT INTO fin.mth_cls_chk_hist(cls_ym,chk_rslt_json,chk_at) VALUES(?,?::jsonb,?::timestamp)","2026-10",json.writeValueAsString(checks.stream().map(c->new ClosingQueryModels.Check(c.code(),"FAIL",3,"나중 점검")).toList()),"2026-10-29 11:00");
        var result=new ClosingQueryService(closing,json).readDashboardClosing("2026-10");
        assertThat(result.checks()).hasSize(9).allSatisfy(c->{assertThat(c.status()).isEqualTo("OK");assertThat(c.description()).isEqualTo("확정 당시");});
        assertThat(value("SELECT count(*) FROM fin.mth_cls_chk_hist WHERE cls_ym='2026-10'")).isEqualTo("2");
    }
}
