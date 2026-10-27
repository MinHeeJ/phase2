package com.sicost.projectsops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectAssignmentMonthPersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AssignmentTestSupport support;
    AnnotationConfigApplicationContext context;
    MockMvc mvc;
    long project;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(adminUrl != null && !adminUrl.isBlank() && user != null && password != null,
            "Real PostgreSQL requires TEST_DATABASE settings");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        support = new AssignmentTestSupport(source);
        project = support.createProject();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/projectsops/ProjectAssignmentMonthMapper.xml"));
        factory.afterPropertiesSet();
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ProjectAssignmentMonthMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(AssignmentTestSupport.Transactions.class, ProjectAssignmentMonthService.class, PermissionService.class);
        context.registerBean(ProjectAssignmentMonthMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.refresh();
        mvc = MockMvcBuilders.standaloneSetup(new ProjectAssignmentMonthController(context.getBean(ProjectAssignmentMonthService.class)))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close();
        if (support != null) support.close();
        if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    long assignment(long projectId, long developer, String status, String start, String end) throws Exception {
        try (var c = source.getConnection(); var s = c.prepareStatement("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES(?,?,?,'BE',CAST(? AS date),CAST(? AS date),?) RETURNING asgn_id")) {
            s.setString(1, "A-" + UUID.randomUUID().toString().substring(0, 16)); s.setLong(2, projectId); s.setLong(3, developer);
            s.setString(4, start); s.setString(5, end); s.setString(6, status);
            try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    void sql(String sql) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(sql); }
    }
    void month(long assignment, String month, String mm, String manual) throws Exception {
        try (var c = source.getConnection(); var s = c.prepareStatement("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty,auto_mm_qty,mnl_yn) VALUES(?,?,CAST(? AS numeric),0.50,?)")) {
            s.setLong(1, assignment); s.setString(2, month); s.setString(3, mm); s.setString(4, manual); s.executeUpdate();
        }
    }
    String snapshot() throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT json_build_object('months',(SELECT json_agg(m ORDER BY asgn_id,work_ym) FROM asgn.asgn_mth m),'assignments',(SELECT json_agg(a ORDER BY asgn_id) FROM asgn.asgn a),'history',(SELECT json_agg(h ORDER BY asgn_chg_id) FROM asgn.asgn_chg_hist h),'rates',(SELECT json_agg(u ORDER BY asgn_id,aply_bgn_dt) FROM asgn.untprc_hist u))::text")) {
            r.next(); return r.getString(1);
        }
    }
    JsonNode read(int year) throws Exception {
        var result = mvc.perform(get("/api/projects/{id}/assignment-months", project).param("year", String.valueOf(year)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
        return support.json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
    List<Long> threeAssignments() throws Exception {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 3; i++) {
            long id = assignment(project, support.createDeveloper(), "ACTIVE", "2026-01-01", "2026-12-31");
            ids.add(id);
            month(id, "2026-01", "0.25", "Y"); month(id, "2026-10", "0.50", "N");
            sql("INSERT INTO asgn.asgn_chg_hist(asgn_id,asgn_chg_ty_cd,aply_dt,aft_val) VALUES("+id+",'START','2026-01-01','시험 등록')");
            sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt) VALUES("+id+",'2026-10-01')");
        }
        return ids;
    }
    @Test void anonymousThreeAssignmentsReadStoredManualAndClosedCellsAndExactTotals() throws Exception {
        threeAssignments(); var data = read(2026);
        assertThat(data.path("prj_id").asLong()).isEqualTo(project);
        assertThat(data.path("items").size()).isEqualTo(3);
        for (var row : data.path("items")) {
            assertThat(row.path("months").size()).isEqualTo(12);
            assertThat(row.path("total_mm_qty").decimalValue()).isEqualByComparingTo("0.75");
            for (int m = 0; m < 12; m++) {
                assertThat(row.path("months").get(m).path("work_ym").asText()).isEqualTo(String.format("2026-%02d",m+1));
                assertThat(row.path("months").get(m).path("mm_qty").decimalValue())
                    .isEqualByComparingTo(m == 0 ? "0.25" : m == 9 ? "0.50" : "0");
            }
        }
        assertThat(data.path("month_totals").size()).isEqualTo(12);
        for (int m = 0; m < 12; m++) assertThat(data.path("month_totals").get(m).path("mm_qty").decimalValue())
            .isEqualByComparingTo(m == 0 ? "0.75" : m == 9 ? "1.50" : "0");
    }
    @Test void cancellationIsExcludedFromRowsAndTotals() throws Exception {
        var ids = threeAssignments();
        sql("UPDATE asgn.asgn SET asgn_stts_cd='CANCELLED' WHERE asgn_id="+ids.get(0));
        var data = read(2026);
        assertThat(data.path("items").size()).isEqualTo(2);
        for (var row : data.path("items")) assertThat(row.path("asgn_id").asLong()).isNotEqualTo(ids.get(0));
        assertThat(data.path("month_totals").get(0).path("mm_qty").decimalValue()).isEqualByComparingTo("0.50");
    }
    @Test void readNeverCreatesOrRecalculatesMonthsOrChangesManualFlags() throws Exception {
        threeAssignments(); String before = snapshot(); read(2026); read(2025);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void missingProjectIs404WithNoWrites() throws Exception {
        threeAssignments(); String before = snapshot();
        mvc.perform(get("/api/projects/{id}/assignment-months", Long.MAX_VALUE).param("year", "2026"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void missingYearIs400WithYearFieldAndNoWrites() throws Exception {
        threeAssignments(); String before = snapshot();
        mvc.perform(get("/api/projects/{id}/assignment-months", project)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("year"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void scopeAndInclusiveOverlapKeepReplacedAssignmentsAndZeroRows() throws Exception {
        long dev = support.createDeveloper();
        long first = assignment(project, dev, "REPLACED", "2025-12-01", "2026-01-01");
        long last = assignment(project, dev, "ACTIVE", "2026-12-31", "2027-01-01");
        assignment(project, dev, "ACTIVE", "2025-01-01", "2025-12-31");
        assignment(project, dev, "ACTIVE", "2027-01-01", "2027-12-31");
        long other = assignment(support.createProject(), dev, "ACTIVE", "2026-01-01", "2026-12-31");
        month(other, "2026-01", "1.00", "N");
        var data = read(2026);
        assertThat(data.path("items").size()).isEqualTo(2);
        assertThat(data.path("items").get(0).path("asgn_id").asLong()).isEqualTo(first);
        assertThat(data.path("items").get(1).path("asgn_id").asLong()).isEqualTo(last);
        for (var row : data.path("items")) assertThat(row.path("months").size()).isEqualTo(12);
        for (var cell : data.path("month_totals")) assertThat(cell.path("mm_qty").decimalValue()).isZero();
    }
    @Test void projectWithoutAssignmentsReturnsEmptyItemsAndTwelveTotals() throws Exception {
        var data = read(2026); assertThat(data.path("items").size()).isZero();
        assertThat(data.path("month_totals").size()).isEqualTo(12);
    }
}
