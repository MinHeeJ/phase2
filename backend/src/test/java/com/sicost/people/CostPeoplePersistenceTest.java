package com.sicost.people;

import com.sicost.common.costallocation.*;
import com.sicost.common.security.PermissionService;
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
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CostPeoplePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    CostPeopleService service;
    CostPeopleMapper mapper;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/people/CostPeopleMapper.xml"),
            new ClassPathResource("mappers/costallocation/CostAllocationMapper.xml"));
        factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(CostPeopleMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, CostPeopleService.class, CostAllocationService.class,
            CostAllocationEngine.class, PermissionService.class);
        context.registerBean(CostPeopleMapper.class, () -> mapper);
        context.registerBean(CostAllocationMapper.class, () -> session.getMapper(CostAllocationMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        context.refresh();
        service = context.getBean(CostPeopleService.class);
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,main_job_cd,hire_dt) VALUES(90001,'K-90001','개인 원가 검증','FT','BE','2026-10-01')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(90001,'P-26901','개인 배분','MO','RUN')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(90001,'A-90001',90001,90001,'BE','2026-10-01','2026-10-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90001,'2026-10',0.40)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(90001,'2026-10-01',0)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt,alwnc_amt,sins_cmpny_amt,sins_emp_amt,whtax_amt,rtrpay_acml_amt) VALUES(90001,'2026-10','FT',100,10,20,3,4,5)");
        sql("INSERT INTO cst.drct_expns(expns_ym,dev_id,expns_item_cd,expns_amt,vat_amt) VALUES('2026-10',90001,'TEAM',10,1),('2026-10',90001,'PEXP',20,2)");
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
    @Test void postgresMaterializesPayrollAndConservesCostWithoutWrites() throws Exception {
        var payroll = mapper.payroll("2026-10").stream().filter(p -> p.dev_id() == 90001).findFirst().orElseThrow();
        assertThat(payroll.slry_amt()).isEqualTo(100L);
        assertThat(payroll.alwnc_amt()).isEqualTo(10L);
        assertThat(payroll.sins_cmpny_amt()).isEqualTo(20L);
        assertThat(payroll.sins_emp_amt()).isEqualTo(3L);
        assertThat(payroll.whtax_amt()).isEqualTo(4L);
        assertThat(payroll.rtrpay_acml_amt()).isEqualTo(5L);
        assertThat(payroll.real_pay_amt()).isEqualTo(103L);
        assertThat(payroll.indv_lbr_cst_amt()).isEqualTo(135L);
        assertThat(payroll.team_expns_amt()).isEqualTo(10);
        assertThat(payroll.project_expns_amt()).isEqualTo(20);
        var query = CostPeopleInput.parse(Map.of("year", "2026", "job", "BE"), context.getBean(Clock.class));
        var row = service.list(query).items().stream().filter(r -> r.dev_id() == 90001).findFirst().orElseThrow();
        assertThat(row.total_cost_amt()).isEqualByComparingTo("165");
        assertThat(row.profit_amt()).isEqualByComparingTo("-165");
        assertThat(service.breakdown(90001, query).items().stream().mapToLong(a -> a.lbr_cst_amt()).sum()).isEqualTo(135);
        assertThat(mapper.projects("2026-10")).anySatisfy(p -> {
            assertThat(p.asgn_id()).isEqualTo(90001);
            assertThat(p.prj_nm()).isEqualTo("개인 배분");
        });
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM cst.cst_aloc WHERE dev_id=90001")) {
            r.next(); assertThat(r.getLong(1)).isZero();
        }
    }
}
