package com.sicost.board;

import com.sicost.assignments.*;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.WorkforcePlanningAssertions;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.CurrentUser;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkforceBoardPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;
    AnnotationConfigApplicationContext context;
    MockMvc mvc;
    long developer;
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null && !adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null && password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures=new AssignmentTestSupport(source); developer=fixtures.createDeveloper();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/board/WorkforceBoardMapper.xml"),
            new ClassPathResource("mappers/assignments/AssignmentMapper.xml"));
        factory.afterPropertiesSet(); var session=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        context.register(AssignmentTestSupport.Transactions.class,WorkforceBoardService.class,
            WorkforceBoardExportService.class,ExcelExportService.class,CurrentUser.class);
        context.registerBean(WorkforceBoardMapper.class,() -> session.getMapper(WorkforceBoardMapper.class));
        context.registerBean(AssignmentMapper.class,() -> session.getMapper(AssignmentMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,() -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,() -> AssignmentTestSupport.CLOCK); context.refresh();
        mvc=MockMvcBuilders.standaloneSetup(new WorkforceBoardController(context.getBean(WorkforceBoardService.class),
            context.getBean(WorkforceBoardExportService.class))).setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void cleanup() throws Exception {
        if(context!=null)context.close(); if(fixtures!=null)fixtures.close(); if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    @Test void actualSqlAppliesAllFiltersAndKeepsClosedAndBusinessHistoryUnchanged() throws Exception {
        jdbc.update("INSERT INTO mst.dev_skl(dev_id,skl_cd) VALUES(?,'JAVA')",developer);
        var closed=WorkforcePlanningAssertions.captureClosedSources(jdbc);
        var before=businessSources();
        mvc.perform(get("/api/workforce/board").param("year","2026").param("job","BE")
            .param("skill","JAVA").param("emp_se_cd","FT").param("free_only","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].dev_id").value(developer));
        mvc.perform(get("/api/workforce/board/export").param("year","2026").param("job","BE")
            .param("skill","JAVA").param("emp_se_cd","FT").param("free_only","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content_base64").isNotEmpty());
        mvc.perform(get("/api/workforce/board").param("year","2026").param("grade","NO_RESULT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.total").value(0));
        assertThat(businessSources()).isEqualTo(before); closed.assertUnchanged(jdbc);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys.acs_hist WHERE scrn_id='UI-28' AND acs_ty_cd='DOWNLOAD'",Long.class)).isEqualTo(1);
    }
    @Test void invalidRequestsDoNotTouchSourcesOrDownloadHistory() throws Exception {
        var before=businessSources();
        for(String path:List.of("/api/workforce/board","/api/workforce/board/export"))
            mvc.perform(get(path).param("year","2026-13")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("year")).andExpect(jsonPath("$.requestId").isNotEmpty());
        assertThat(businessSources()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys.acs_hist WHERE scrn_id='UI-28'",Long.class)).isZero();
    }
    private Map<String,String> businessSources() {
        var rows=new LinkedHashMap<String,String>();
        for(String table:List.of("mst.dev","mst.dev_skl","mst.dev_work_stts_hist","asgn.asgn","asgn.asgn_mth",
                "asgn.tmpr_asgn","sys.data_chg_hist","bsn.oppt","bsn.oppt_need"))
            rows.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+table+" t",String.class));
        return rows;
    }
}
