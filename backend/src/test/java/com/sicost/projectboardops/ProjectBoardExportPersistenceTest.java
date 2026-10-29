package com.sicost.projectboardops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.projectcost.*;
import java.io.ByteArrayInputStream;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class ProjectBoardExportPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ProjectBoardExportService service;
    ProjectBoardExportMapper mapper;
    ProjectCostService costs;
    MockMvc mvc;
    Clock clock=Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneOffset.UTC);
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
        factory.setMapperLocations(new ClassPathResource("mappers/projectboardops/ProjectBoardExportMapper.xml"),
            new ClassPathResource("mappers/projectcost/ProjectCostMapper.xml"),
            new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"),
            new ClassPathResource("mappers/costallocation/CostAllocationMapper.xml"));
        factory.afterPropertiesSet(); var session=new SqlSessionTemplate(factory.getObject());
        mapper=session.getMapper(ProjectBoardExportMapper.class);
        var costMapper=session.getMapper(com.sicost.apiops.ProjectBoardMapper.class);
        context=new AnnotationConfigApplicationContext();
        com.sicost.common.CostAllocationTestSupport.register(context, session);
        context.register(Transactions.class,ProjectBoardExportService.class,ProjectCostService.class,
            com.sicost.apiops.ProjectCostService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class);
        context.registerBean(ProjectBoardExportMapper.class,()->mapper);
        context.registerBean(com.sicost.apiops.ProjectBoardMapper.class,()->costMapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->clock); context.refresh();
        service=context.getBean(ProjectBoardExportService.class); costs=context.getBean(ProjectCostService.class);
        mvc=MockMvcBuilders.standaloneSetup(new ProjectBoardExportController(service))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cur_cntrct_amt,prfm_bgn_dt) VALUES(1,'P-2601','원가 대상','TK','RUN',10000000,'2026-10-01'),(2,'P-2602','다른 프로젝트','MO','END',10000000,'2026-10-01')");
        sql("UPDATE prj.prj SET trgt_cst_amt=8000000 WHERE prj_id IN (1,2)");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'K-0001','개발자','FT')");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close(); if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String query,Object... args) { new JdbcTemplate(source).update(query,args); }
    String value(String query) { return new JdbcTemplate(source).queryForObject(query,String.class); }
    String snapshot() {
        return value("SELECT json_build_object('projects',(SELECT json_agg(p ORDER BY prj_id) FROM prj.prj p),'phases',(SELECT json_agg(p ORDER BY phs_id) FROM prj.phs p),'memos',(SELECT json_agg(p ORDER BY memo_id) FROM prj.wkly_memo p),'costs',(SELECT json_agg(p ORDER BY cst_aloc_id) FROM cst.cst_aloc p),'history',(SELECT json_agg(p ORDER BY data_chg_hist_id) FROM sys.data_chg_hist p))::text");
    }
    void assignment(int id,int project,String status,String month,String mm) {
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES(?, ?, ?,1,'BE','2026-01-01','2026-12-31',?)",id,"A-"+id,project,status);
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(?,?,CAST(? AS numeric))",id,month,mm);
    }
    XSSFWorkbook exported(Map<String,String> filters) throws Exception {
        var request=get("/api/project-board/export"); filters.forEach(request::param);
        var response=mvc.perform(request).andExpect(status().isOk()).andExpect(content().contentType(DownloadArtifact.XLSX)).andReturn();
        return new XSSFWorkbook(new ByteArrayInputStream(response.getResponse().getContentAsByteArray()));
    }
    @Test void anonymousExportReadsRealSourcesAndPersistsOnlyDownloadMetadata() throws Exception {
        sql("INSERT INTO prj.phs(prj_id,phs_seq,phs_nm,wght_rt,plan_bgn_dt,plan_end_dt) VALUES(1,1,'분석',100,'2026-10-01','2026-10-31')");
        sql("INSERT INTO prj.wkly_memo(prj_id,wrt_dt,actl_prgs_rt) VALUES(1,'2026-10-22',42.5)");
        sql("INSERT INTO cst.drct_expns(expns_ym,prj_id,expns_item_cd,expns_amt,vat_amt) VALUES('2026-10',1,'TRIP',200000,20000)");
        String before=snapshot();
        try(var book=exported(Map.of("type","TK"))) {
            var row=book.getSheetAt(0).getRow(4);
            assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(71.0);
            assertThat(row.getCell(7).getNumericCellValue()).isEqualTo(42.5);
            assertThat(row.getCell(12).getNumericCellValue()).isEqualTo(200000);
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(value("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-31' AND user_id IS NULL")).isEqualTo("1");
        var metadata=new ObjectMapper().readTree(value("SELECT dtl_desc FROM sys.acs_hist WHERE scrn_id='UI-31'"));
        assertThat(metadata.path("filename").asText()).isEqualTo("프로젝트현황판_20261023.xlsx");
        assertThat(metadata.path("columns").get(12).path("sensitive").asBoolean()).isTrue();
        assertThat(metadata.path("columns").get(7).path("sensitive").asBoolean()).isFalse();
        assertThat(costs.calculate(1,YearMonth.of(2026,10)).cumulativeCostAmount()).isEqualTo(200000);
    }
    @Test void costUsesAllProjectDenominatorAggregatesAssignmentsAndFloorsOncePerPersonMonth() {
        assignment(1,1,"ACTIVE","2026-10","0.20"); assignment(2,1,"ENDED","2026-10","0.30");
        assignment(3,2,"REPLACED","2026-10","1.00"); assignment(4,2,"CANCELLED","2026-10","9.00");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(1,'2026-10','FT',9940000)");
        assertThat(costs.calculate(1,YearMonth.of(2026,10)).cumulativeCostAmount()).isEqualTo(3313333);
        assertThat(costs.calculate(1,YearMonth.of(2026,10)).missingSalaryMonths()).isEmpty();
    }
    @Test void closedAllocationReplacesBothLaborAndExpensesAndSuppressesMissingSalary() {
        assignment(1,1,"ACTIVE","2026-09","0.50");
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-09','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        sql("INSERT INTO cst.drct_expns(expns_ym,prj_id,expns_item_cd,expns_amt) VALUES('2026-09',1,'TRIP',999999),('2026-10',1,'TRIP',50),('2026-11',1,'TRIP',999999)");
        sql("INSERT INTO cst.cst_aloc(cst_ym,dev_id,prj_id,aloc_ty_cd,lbr_cst_amt,drct_expns_amt) VALUES('2026-09',1,1,'PROJECT',1000,100),('2026-09',1,1,'PROJECT',2000,200),('2026-11',1,1,'PROJECT',999999,0)");
        var result=costs.calculate(1,YearMonth.of(2026,10));
        assertThat(result.cumulativeCostAmount()).isEqualTo(3350); assertThat(result.missingSalaryMonths()).isEmpty();
    }
    @Test void closedWithoutAllocationFailsAndOpenIgnoresOldSnapshotWithNoLowerBound() {
        assignment(1,1,"ACTIVE","2020-01","0.50"); assignment(2,1,"ACTIVE","2026-10","0.50");
        sql("UPDATE asgn.asgn SET bgn_dt='2020-01-01',end_dt='2020-01-31' WHERE asgn_id=1");
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2020-01','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(1,'2020-01','FT',1000),(1,'2026-10','FT',1000)");
        sql("INSERT INTO cst.cst_aloc(cst_ym,dev_id,prj_id,aloc_ty_cd,lbr_cst_amt) VALUES('2026-10',1,1,'PROJECT',999999)");
        assertThatThrownBy(() -> costs.calculate(1,YearMonth.of(2026,10))).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("CLOSED_MONTH"));
        sql("UPDATE fin.mth_cls SET cls_stts_cd='OPEN' WHERE cls_ym='2020-01'");
        assertThat(costs.calculate(1,YearMonth.of(2026,10)).cumulativeCostAmount()).isEqualTo(1000);
    }
    @Test void missingSalaryZeroesOnlyLaborReportsDistinctSortedPositiveMonthsAndIgnoresFuture() {
        assignment(1,1,"ACTIVE","2026-08","0.50"); assignment(2,1,"ACTIVE","2026-08","0.20");
        assignment(3,1,"ACTIVE","2026-09","0.00"); assignment(4,1,"ACTIVE","2026-10","0.50");
        assignment(5,1,"ACTIVE","2026-11","0.50");
        sql("INSERT INTO cst.drct_expns(expns_ym,prj_id,expns_item_cd,expns_amt) VALUES('2026-08',1,'TRIP',123)");
        sql("UPDATE fin.mth_cls SET cls_stts_cd='OPEN' WHERE cls_ym IN ('2026-08','2026-09')");
        var result=costs.calculate(1,YearMonth.of(2026,10));
        assertThat(result.cumulativeCostAmount()).isEqualTo(123);
        assertThat(result.missingSalaryMonths()).containsExactly("2026-08","2026-10");
    }
    @Test void eligibilityFiltersAndExportFullSetIgnoreProjectYearAndDisabledHoldEndLost() throws Exception {
        sql("UPDATE prj.prj SET prj_stts_cd='PLAN' WHERE prj_id=1");
        try(var book=exported(Map.of())) { assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(3); }
        assignment(1,1,"ACTIVE","2026-10","0.50");
        var query=ProjectBoardExportService.query(Map.of(),LocalDate.of(2026,10,23));
        assertThat(mapper.sources(query)).extracting(ProjectBoardExportModels.BoardExportSource::prj_id).containsExactly(1L);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cntrct_bgn_dt,cntrct_end_dt) SELECT n+2,'P-'||(2700+n),'대상'||n,'MO','RUN','2020-01-01','2020-12-31' FROM generate_series(1,101) n");
        try(var book=exported(Map.of("type","MO"))) { assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(104); }
        assertThat(mapper.sources(ProjectBoardExportService.query(Map.of("part","999999"),query.today()))).isEmpty();
        for(String status:List.of("HOLD","END","LOST")) {
            sql("UPDATE prj.prj SET prj_stts_cd=? WHERE prj_id=1",status);
            assertThat(mapper.sources(ProjectBoardExportService.query(Map.of("type","TK"),query.today()))).isEmpty();
        }
        sql("UPDATE prj.prj SET prj_stts_cd='RUN',use_yn='N' WHERE prj_id=1");
        assertThat(mapper.sources(ProjectBoardExportService.query(Map.of("type","TK"),query.today()))).isEmpty();
    }
    @Test void nullPhaseDatesAndZeroPhasesArePreservedAndLatestDecimalMemoIsNotTruncated() {
        sql("INSERT INTO prj.phs(prj_id,phs_seq,phs_nm,wght_rt,cmpl_dt) VALUES(1,1,'완료',50,'2026-10-20'),(1,2,'예정',50,NULL)");
        sql("INSERT INTO prj.wkly_memo(prj_id,wrt_dt,actl_prgs_rt) VALUES(1,'2026-10-21',12),(1,'2026-10-22',42.5)");
        var query=ProjectBoardExportService.query(Map.of(),LocalDate.of(2026,10,23));
        var row=mapper.sources(query).get(0);
        assertThat(row.plan_prgs_rt()).isEqualByComparingTo("0"); assertThat(row.memoProgress()).isEqualByComparingTo("42.5");
        assertThat(row.currentPhase()).isEqualTo("예정");
        sql("UPDATE prj.phs SET cmpl_dt='2026-10-23' WHERE prj_id=1");
        assertThat(mapper.sources(query).get(0).currentPhase()).isEqualTo("예정");
        sql("DELETE FROM prj.phs WHERE prj_id=1");
        assertThat(mapper.sources(query).get(0).currentPhase()).isNull();
        assertThat(mapper.sources(query).get(0).phaseCount()).isZero();
    }
    @Test void invalidFilterAndMissingParameterLeaveBusinessAndDownloadHistoryUnchanged() throws Exception {
        String before=snapshot();
        mvc.perform(get("/api/project-board/export").param("light","bad")).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
        sql("DELETE FROM cmn.oprt_std WHERE oprt_std_id='BUDGET_RATE'");
        mvc.perform(get("/api/project-board/export")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
        assertThat(snapshot()).isEqualTo(before); assertThat(value("SELECT count(*) FROM sys.acs_hist WHERE scrn_id='UI-31'")).isEqualTo("0");
    }
    @Test void downloadAuditFailureRollsBackAndDoesNotReturnWorkbookOrMutateCosts() throws Exception {
        String before=snapshot();
        sql("ALTER TABLE sys.acs_hist ADD CONSTRAINT reject_board_download CHECK(scrn_id!='UI-31') NOT VALID");
        assertThatThrownBy(()->service.export(Map.of())).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before); assertThat(value("SELECT count(*) FROM sys.acs_hist WHERE scrn_id='UI-31'")).isEqualTo("0");
    }
}
