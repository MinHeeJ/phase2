package com.sicost.projectsops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.apiops.*;
import com.sicost.common.api.*;
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

class ProjectProgressPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    MockMvc mvc;
    ProjectProgressService service;
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
        factory.setMapperLocations(new ClassPathResource("mappers/projectsops/ProjectProgressMapper.xml"),new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"),
            new ClassPathResource("mappers/costallocation/CostAllocationMapper.xml"));
        factory.afterPropertiesSet(); var session=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        com.sicost.common.CostAllocationTestSupport.register(context, session);
        context.register(Transactions.class,ProjectProgressService.class,ProgressParameterService.class,ProjectCostService.class,PermissionService.class);
        context.registerBean(ProjectProgressMapper.class,()->session.getMapper(ProjectProgressMapper.class));
        context.registerBean(ProjectBoardMapper.class,()->session.getMapper(ProjectBoardMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
        context.refresh(); service=context.getBean(ProjectProgressService.class);
        mvc=MockMvcBuilders.standaloneSetup(new ProjectProgressController(service)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        for(var p:ProjectProgressApiTest.params()) sql("INSERT INTO cmn.oprt_std(oprt_std_id,aply_bgn_ym,oprt_std_nm,num_val) VALUES('"+p.oprt_std_id()+"','2026-10','시험',"+p.num_val()+") ON CONFLICT(oprt_std_id,aply_bgn_ym) DO UPDATE SET num_val=excluded.num_val");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cur_cntrct_amt) VALUES(90001,'P-26901','진행 조회 시험','TK','RUN',500000000),(90002,'P-26902','다른 프로젝트','TK','END',500000000)");
        sql("UPDATE prj.prj SET trgt_cst_amt=400000000 WHERE prj_id IN (90001,90002)");
        sql("INSERT INTO prj.phs(prj_id,phs_seq,phs_nm,wght_rt,plan_bgn_dt,plan_end_dt,cmpl_dt) VALUES(90001,1,'분석',40,'2026-01-01','2026-09-01','2026-09-01'),(90001,2,'개발',35,'2026-09-23','2026-12-31',NULL),(90001,3,'종료',25,NULL,NULL,NULL)");
        sql("INSERT INTO prj.wkly_memo(prj_id,wrt_dt,actl_prgs_rt,cmpl_work_desc,plan_work_desc) VALUES(90001,'2026-10-08',20,'지난 일','지난 계획'),(90001,'2026-10-23',42,'한 일','할 일')");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null)context.close(); if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.drop(s,database); }
    }
    void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);} }
    String value(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);} }
    String snapshot() throws Exception { return value("SELECT json_build_object('projects',(SELECT json_agg(p ORDER BY prj_id) FROM prj.prj p),'phases',(SELECT json_agg(p ORDER BY phs_id) FROM prj.phs p),'memos',(SELECT json_agg(m ORDER BY memo_id) FROM prj.wkly_memo m),'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),'allocations',(SELECT json_agg(a ORDER BY cst_aloc_id) FROM cst.cst_aloc a))::text"); }
    @Test void anonymousProgressRealRowsReadbackAndNoWrites() throws Exception {
        String before=snapshot();
        mvc.perform(get("/api/projects/90001/progress")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plan_prgs_rt").value(50.5)).andExpect(jsonPath("$.data.actl_prgs_rt").value(42))
            .andExpect(jsonPath("$.data.schedule_diff_rt").value(-8.5)).andExpect(jsonPath("$.data.light").value("주의"))
            .andExpect(jsonPath("$.data.pmName").value("PM 미지정")).andExpect(jsonPath("$.data.memos[0].actl_prgs_rt").value(42))
            .andExpect(jsonPath("$.data.phases[2].plan_bgn_dt").isEmpty());
        assertThat(service.get(90001).memos()).hasSize(2);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void realIssuesSortClosedLastSeverityThenDeadlineAndExcludeOtherProject() throws Exception {
        sql("INSERT INTO prj.issue(prj_id,ttl_nm,svrt_cd,ddln_dt,issue_stts_cd,reg_dt) VALUES(90001,'중간','MID','2026-10-01','OPEN','2026-10-01'),(90001,'높음 오늘','HIGH','2026-10-23','DOING','2026-10-02'),(90001,'높음 어제','HIGH','2026-10-22','OPEN','2026-10-03'),(90001,'종료','HIGH','2026-10-01','CLOSED','2026-10-01'),(90002,'다른 프로젝트','HIGH','2026-10-01','OPEN','2026-10-01')");
        mvc.perform(get("/api/projects/90001/issues")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.open_count").value(3)).andExpect(jsonPath("$.data.items.length()").value(4))
            .andExpect(jsonPath("$.data.items[0].ttl_nm").value("높음 어제")).andExpect(jsonPath("$.data.items[1].ttl_nm").value("높음 오늘"))
            .andExpect(jsonPath("$.data.items[3].ttl_nm").value("종료"));
        var source=context.getBean(ProjectProgressMapper.class).source(new ProjectProgressModels.ProgressRead(90001,LocalDate.of(2026,10,23)));
        assertThat(source.overdue_issue_count()).isEqualTo(2); assertThat(source.high_overdue_count()).isEqualTo(1);
        assertThat(service.get(90001).light()).isEqualTo("위험");
    }
    @Test void costUsesSingleOwnerAllProjectDenominatorAndClosedMonthReplacement() throws Exception {
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(90001,'K-90001','시험 개발자','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(90001,'A-90001',90001,90001,'BE','2026-01-01','2026-12-31'),(90002,'A-90002',90002,90001,'BE','2026-01-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90001,'2026-10',1),(90002,'2026-10',0.5),(90001,'2026-09',1),(90001,'2026-11',1)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(90001,'2026-10','FT',4970000)");
        assertThatThrownBy(() -> service.get(90001)).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("CLOSED_MONTH"));
        sql("UPDATE fin.mth_cls SET cls_stts_cd='OPEN' WHERE cls_ym='2026-09'");
        assertThat(service.get(90001).cumulative_cost_amt()).isEqualTo(3313333);
        assertThat(service.get(90001).missing_salary_months()).containsExactly("2026-09");
        sql("INSERT INTO cst.drct_expns(expns_ym,prj_id,expns_item_cd,expns_amt) VALUES('2026-09',90001,'TRIP',9999),('2026-11',90001,'TRIP',999999)");
        sql("INSERT INTO cst.cst_aloc(cst_ym,dev_id,prj_id,aloc_ty_cd,lbr_cst_amt,drct_expns_amt) VALUES('2026-09',90001,90001,'PROJECT',2000,500)");
        sql("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-09'");
        String before=snapshot();
        assertThat(service.get(90001).cumulative_cost_amt()).isEqualTo(3315833);
        assertThat(service.get(90001).missing_salary_months()).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void invalidLegacyAndMissingParameterFailWithoutMutation() throws Exception {
        sql("UPDATE prj.wkly_memo SET actl_prgs_rt=42.5 WHERE prj_id=90001 AND wrt_dt='2026-10-23'");
        String before=snapshot();
        mvc.perform(get("/api/projects/90001/progress")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("actl_prgs_rt"));
        assertThat(snapshot()).isEqualTo(before);
        sql("DELETE FROM cmn.oprt_std WHERE oprt_std_id='SIG_COST_RISK'");
        mvc.perform(get("/api/projects/90001/progress")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
        assertThat(snapshot()).isEqualTo(before);
    }
}
