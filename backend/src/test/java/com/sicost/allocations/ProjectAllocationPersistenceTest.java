package com.sicost.allocations;

import static com.sicost.allocations.ProjectAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.sql.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import com.sicost.common.security.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class ProjectAllocationPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ProjectAllocationService service;
    ProjectAllocationMapper mapper;
    org.springframework.test.web.servlet.MockMvc mvc;

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
                var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/allocations/ProjectAllocationMapper.xml"));
        factory.afterPropertiesSet();
        var template = new SqlSessionTemplate(factory.getObject()); mapper = template.getMapper(ProjectAllocationMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, ProjectAllocationService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(ProjectAllocationMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.refresh(); service = context.getBean(ProjectAllocationService.class);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new ProjectAllocationController(service,new com.fasterxml.jackson.databind.ObjectMapper()))
            .setControllerAdvice(new com.sicost.common.api.ApiExceptionHandler())
            .addFilters(new com.sicost.common.api.RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,cntrct_bgn_dt,cntrct_end_dt,cur_cntrct_amt) VALUES(1,'P-2601','프로젝트','TK','2026-01-01','2026-12-31',600000000),(2,'P-2602','다른 프로젝트','TK','2026-01-01','2026-12-31',600000000)");
        sql("INSERT INTO prj.cntrct_chg_hist(prj_id,cntrct_chg_ty_cd,cntrct_chg_stts_cd,chg_dt,realoc_need_yn) VALUES(1,'ADD','CONFIRM','2026-10-01','Y'),(1,'TERM','CONFIRM','2026-10-02','Y'),(2,'ADD','CONFIRM','2026-10-01','Y')");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }
    void sql(String sql) throws Exception { try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(sql); } }
    long scalar(String sql) throws Exception { try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) { assertThat(r.next()).isTrue(); return r.getLong(1); } }
    List<AllocationLine> input(long amount) {
        return List.of(new AllocationLine("ITEM", null, "기술료", null, null, null, null, 0,
                List.of(new AllocationMonth("2026-10", amount, new BigDecimal("1.25")),
                        new AllocationMonth("2026-11", null, null))));
    }
    @Test void selectedStageReplacementRealSqlMaterializationFlagsAndAudit() throws Exception {
        service.save(1, "EST", input(20)); long estimateId = scalar("SELECT aloc_line_id FROM prj.tk_aloc_line WHERE aloc_stg_cd='EST'");
        service.save(1, "FINAL", input(30)); service.save(1, "ACTUAL", input(600000000));
        var actual = service.get(1, null);
        assertThat(actual.activeStage()).isEqualTo("ACTUAL");
        assertThat(actual.items().get(0).months()).containsExactly(new AllocationMonth("2026-10", 600000000L, new BigDecimal("1.25")));
        assertThat(actual.items().get(0).dev_id()).isNull();
        assertThat(scalar("SELECT count(*) FROM prj.cntrct_chg_hist WHERE prj_id=1 AND realoc_need_yn='Y'")).isZero();
        assertThat(scalar("SELECT count(*) FROM prj.cntrct_chg_hist WHERE prj_id=2 AND realoc_need_yn='Y'")).isEqualTo(1);
        assertThat(scalar("SELECT aloc_line_id FROM prj.tk_aloc_line WHERE aloc_stg_cd='EST'")).isEqualTo(estimateId);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.tk_aloc_line' AND obj_rec_id='1' AND clmn_nm='ACTUAL' AND chg_user_id IS NULL")).isEqualTo(1);
        mvc.perform(put("/api/projects/1/allocations").param("stage","ACTUAL").contentType("application/json")
            .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("items",input(590000000)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeStage").value("ACTUAL"));
        assertThat(scalar("SELECT count(*) FROM prj.tk_aloc_line WHERE prj_id=1 AND aloc_stg_cd='ACTUAL'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM prj.tk_aloc_mth m JOIN prj.tk_aloc_line l USING(aloc_line_id) WHERE l.prj_id=1 AND l.aloc_stg_cd='ACTUAL' AND m.aloc_amt=590000000")).isEqualTo(1);
        assertThat(service.get(1, "ACTUAL").items().get(0).months().get(0).aloc_amt()).isEqualTo(590000000);
        assertThat(service.get(1, "FINAL").items().get(0).months().get(0).aloc_amt()).isEqualTo(30);
    }
    @Test void auditFailureRollsBackReplacementAndFlags() throws Exception {
        service.save(1, "ACTUAL", input(10));
        sql("UPDATE prj.cntrct_chg_hist SET realoc_need_yn='Y' WHERE prj_id=1");
        long id = scalar("SELECT aloc_line_id FROM prj.tk_aloc_line WHERE aloc_stg_cd='ACTUAL'");
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(() -> service.save(1, "ACTUAL", input(20))).isInstanceOf(RuntimeException.class);
        assertThat(scalar("SELECT aloc_line_id FROM prj.tk_aloc_line WHERE aloc_stg_cd='ACTUAL'")).isEqualTo(id);
        assertThat(service.get(1, null).items().get(0).months().get(0).aloc_amt()).isEqualTo(10);
        assertThat(service.get(1, null).realoc_need_yn()).isEqualTo("Y");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(1);
    }
    @Test void periodShrinkReadPreserveRemoveAndNoNewOutsideCells() throws Exception {
        service.save(1, "EST", input(10));
        sql("UPDATE prj.prj SET cur_end_dt='2026-09-30' WHERE prj_id=1");
        var existing = service.get(1, "EST");
        service.save(1, "EST", existing.items());
        assertThatThrownBy(() -> service.save(1, "EST", input(20))).isInstanceOf(com.sicost.common.api.ApiException.class);
        service.save(1, "EST", List.of());
        assertThat(service.get(1, null).items()).isEmpty();
        assertThat(scalar("SELECT count(*) FROM prj.tk_aloc_mth")).isZero();
    }
}
