package com.sicost.projects;

import com.sicost.apiops.*;
import com.sicost.billingsops.*;
import com.sicost.common.api.*;
import com.sicost.common.costs.CostStandards;
import com.sicost.common.security.*;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectProfitPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    MockMvc mvc;
    ProjectProfitService service;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.create(s,database); }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/projects/ProjectProfitMapper.xml"),
            new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"),new ClassPathResource("mappers/billingsops/BillingMapper.xml"));
        factory.afterPropertiesSet(); var session=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,ProjectProfitService.class,ProjectCostService.class,CostStandards.class,PermissionService.class);
        context.registerBean(ProjectProfitMapper.class,()->session.getMapper(ProjectProfitMapper.class));
        context.registerBean(ProjectBoardMapper.class,()->session.getMapper(ProjectBoardMapper.class));
        var clock=Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));
        context.registerBean(BillingRevenueQuery.class,()->new BillingRevenueQuery(session.getMapper(BillingMapper.class),
            mock(BillingReceivableService.class),clock,new PermissionService()));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->clock);
        context.refresh(); service=context.getBean(ProjectProfitService.class);
        mvc=MockMvcBuilders.standaloneSetup(new ProjectProfitController(service)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cur_cntrct_amt,trgt_cst_amt,trgt_mrgn_rt,cntrct_bgn_dt,cur_end_dt) VALUES(91001,'P-26911','원가 시험','TK','RUN',500,400,20,'2026-01-01','2026-12-31'),(91002,'P-26912','다른 사업','MO','RUN',500,350,30,'2026-01-01','2026-12-31')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(91001,'K-91001','원가 개발자','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(91001,'A-91001',91001,91001,'BE','2026-01-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(91001,'2026-10',1)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(91001,'2026-10','FT',300)");
        sql("INSERT INTO cst.drct_expns(expns_ym,prj_id,expns_item_cd,expns_amt) VALUES('2026-10',91001,'TRIP',20)");
        sql("INSERT INTO bill.bill(bill_no,prj_id,bill_dt,sply_amt,bill_stts_cd) VALUES('B-2610-91001',91001,'2026-10-01',220,'CONFIRM'),('B-2610-91002',91001,'2026-10-02',-20,'CONFIRM'),('B-2610-91003',91001,'2026-10-03',999,'DRAFT')");
        sql("INSERT INTO prj.psn_plan(prj_id,plan_ym,job_cd,need_mm_qty) VALUES(91001,'2026-10','BE',1)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null)context.close(); if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.drop(s,database); }
    }
    void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);} }
    String snapshot() throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT json_build_object('projects',(SELECT json_agg(p ORDER BY prj_id) FROM prj.prj p),'salary',(SELECT json_agg(p ORDER BY mth_cst_id) FROM cst.mth_cst p),'expense',(SELECT json_agg(p ORDER BY expns_id) FROM cst.drct_expns p),'bills',(SELECT json_agg(p ORDER BY bill_id) FROM bill.bill p),'allocation',(SELECT json_agg(p ORDER BY cst_aloc_id) FROM cst.cst_aloc p),'history',(SELECT json_agg(p ORDER BY data_chg_hist_id) FROM sys.data_chg_hist p),'access',(SELECT json_agg(p ORDER BY acs_hist_id) FROM sys.acs_hist p))::text")) {
            r.next(); return r.getString(1);
        }
    }
    @Test void realReadPathConfirmedNegativeRevenueAnnualEqualityAndNoWrites() throws Exception {
        String before=snapshot();
        var detail=service.get("91001","2026");
        assertThat(detail.summary().cost()).isEqualTo(320);
        assertThat(detail.summary().revenue()).isEqualTo(200);
        assertThat(detail.summary().profit()).isEqualTo(-120);
        assertThat(detail.summary().budget_rate()).isEqualByComparingTo("80.0");
        assertThat(detail.months().stream().mapToLong(ProjectProfitModels.Month::cost).sum()).isEqualTo(detail.summary().cost());
        assertThat(context.getBean(ProjectCostService.class).calculateAnnual(91001,YearMonth.of(2026,10)).cumulative_cost_amt()).isEqualTo(detail.summary().cost());
        mvc.perform(post("/api/costs/projects/91001/simulate").contentType("application/json").content("{\"unsupported\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void filteredListAndCountUseSameSetAndClosedMissingNeverFallsBack() throws Exception {
        var page=service.list(Map.of("year","2026","q","원가 시험","type","TK","status","RUN"));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        String before=snapshot();
        mvc.perform(get("/api/costs/projects/91001").param("year","2026")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("마감 배분자료 없음")));
        assertThat(snapshot()).isEqualTo(before);
    }
}
