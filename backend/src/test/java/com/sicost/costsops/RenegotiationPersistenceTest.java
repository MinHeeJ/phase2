package com.sicost.costsops;

import com.sicost.common.security.PermissionService;
import com.sicost.costallocation.*;
import com.sicost.shared.SharedReadMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.costsops.RenegotiationModels.*;

class RenegotiationPersistenceTest {
    @Configuration("renegotiationTestTransactions") @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    RenegotiationMapper mapper;
    RenegotiationService service;
    Clock clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl,user,password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement,database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/costsops/RenegotiationMapper.xml"),
            new ClassPathResource("mappers/costallocation/CostAllocationMapper.xml"),
            new ClassPathResource("mappers/shared/SharedReadMapper.xml"));
        factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(RenegotiationMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class,RenegotiationService.class,CostAllocationService.class,PermissionService.class);
        context.registerBean(RenegotiationMapper.class, () -> mapper);
        context.registerBean(CostAllocationMapper.class, () -> session.getMapper(CostAllocationMapper.class));
        context.registerBean(SharedReadMapper.class, () -> session.getMapper(SharedReadMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, () -> clock);
        context.refresh();
        service = context.getBean(RenegotiationService.class);
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(901,'K-00901','재협상 검증','FT')");
        for (int id=901;id<=909;id++) {
            String type = id==903 ? "TK" : id==904 ? "IN" : id==902 ? "DP" : "MO";
            String state = id==905 ? "END" : "RUN";
            sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES("+id+",'P-"+id+"','검증 "+id+"','"+type+"','"+state+"')");
            String kind = id==906 ? "FREE" : "NEW";
            String status = id==907 ? "CANCELLED" : "ACTIVE";
            String end = id==908 ? "2026-10-28" : id==909 ? "2027-06-01" : "2026-12-31";
            sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_se_cd,asgn_stts_cd) VALUES("+id+",'A-"+id+"',"+id+",901,'BE','2026-01-01','"+end+"','"+kind+"','"+status+"')");
            sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES("+id+",'2026-10',0.50)");
            sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES("+id+",'2026-01-01',1000)");
        }
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(901,'2026-10','FT',8000)");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context != null) context.close();
        if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl,user,password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    void sql(String sql) { new JdbcTemplate(source).execute(sql); }
    String snapshot() {
        return new JdbcTemplate(source).queryForObject("SELECT json_build_object('pay',(SELECT json_agg(c) FROM cst.mth_cst c), 'mm',(SELECT json_agg(m) FROM asgn.asgn_mth m), 'rate',(SELECT json_agg(h) FROM asgn.untprc_hist h), 'allocation',(SELECT json_agg(a) FROM cst.cst_aloc a))::text",String.class);
    }
    @Test void selectedEngineAppliesEveryEligibilityRuleAndMaterializesActualIdentity() {
        var today = LocalDate.of(2026,10,29);
        var rows = mapper.candidates(new SourceQuery(today,today.plusMonths(6),"2026-10",null,null));
        assertThat(rows).extracting(Source::asgn_id).containsExactly(901L,902L);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.dev_id()).isEqualTo(901);
            assertThat(r.dev_nm()).isEqualTo("재협상 검증");
            assertThat(r.emp_se_cd()).isEqualTo("FT");
            assertThat(r.end_dt()).isEqualTo(LocalDate.of(2026,12,31));
        });
        assertThat(mapper.candidates(new SourceQuery(today,today.plusMonths(3),"2026-10","OS",null))).isEmpty();
        assertThat(mapper.candidates(new SourceQuery(today,today.plusMonths(3),"2026-10","FT",902L)))
            .extracting(Source::asgn_id).containsExactly(902L);
    }
    @Test void repeatedSimulationNeverMutatesPayrollMmRatesOrSnapshots() {
        String before = snapshot();
        var first = service.list(RenegotiationInput.query(Map.of()),false);
        var raised = service.list(RenegotiationInput.query(Map.of("raiseRate","50")),false);
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.summary().psn_cnt()).isEqualTo(1);
        assertThat(raised.summary().assumed_annual_profit_increase_amt())
            .isGreaterThan(first.summary().assumed_annual_profit_increase_amt());
        assertThat(snapshot()).isEqualTo(before);
        assertThat(service.list(RenegotiationInput.query(Map.of()),false)).isEqualTo(first);
    }
}
