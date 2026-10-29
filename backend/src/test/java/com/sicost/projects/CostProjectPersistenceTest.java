package com.sicost.projects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.billingsops.*;
import com.sicost.common.api.*;
import com.sicost.common.costallocation.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.projects.CostProjectModels.*;

class CostProjectPersistenceTest {
    @Configuration("costProjectsTransactions") @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    CostProjectService service;
    CostProjectMapper mapper;
    MockMvc mvc;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null && !adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null && password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/projects/CostProjectMapper.xml"),new ClassPathResource("mappers/costallocation/CostAllocationMapper.xml"),new ClassPathResource("mappers/billingsops/BillingMapper.xml"));
        factory.afterPropertiesSet();var session=new SqlSessionTemplate(factory.getObject());
        mapper=session.getMapper(CostProjectMapper.class);
        context=new AnnotationConfigApplicationContext();
        com.sicost.common.CostAllocationTestSupport.register(context,session);
        context.register(Transactions.class,CostProjectService.class,CostProjectExportService.class,BillingRevenueQuery.class,
            BillingReceivableService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class,com.sicost.apiops.ProjectCostService.class);
        context.registerBean(CostProjectMapper.class,()->mapper);
        context.registerBean(BillingMapper.class,()->session.getMapper(BillingMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();service=context.getBean(CostProjectService.class);
        mvc=MockMvcBuilders.standaloneSetup(new CostProjectController(service,context.getBean(CostProjectExportService.class),json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(90017,'K-90017','원가 조회 인력','FL')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cntrct_bgn_dt,cntrct_end_dt,cur_end_dt,init_cntrct_amt,cur_cntrct_amt,trgt_cst_amt,trgt_mrgn_rt) VALUES(90017,'P-26917','분석 대상','TK','RUN','2026-10-01','2026-12-31','2026-12-31',500,500,400,20),(90018,'P-26918','다른 분석','MO','END','2025-01-01','2025-12-31','2025-12-31',100,100,80,20)");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(90017,'A-90017',90017,90017,'BE','2026-10-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90017,'2026-10',1)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(90017,'2026-10','FL',300)");
        sql("INSERT INTO cst.drct_expns(expns_ym,prj_id,expns_item_cd,expns_amt,vat_amt) VALUES('2026-10',90017,'TRIP',20,2)");
        sql("INSERT INTO bill.bill(bill_id,bill_no,prj_id,bill_dt,sply_amt,vat_amt,bill_stts_cd) VALUES(90017,'B-2610-917',90017,'2026-10-01',210,21,'CONFIRM')");
        sql("INSERT INTO bill.bill(bill_no,prj_id,bill_dt,sply_amt,vat_amt,bill_stts_cd,amnd_yn,orgnl_bill_id) VALUES('B-2610-918',90017,'2026-10-02',-10,-1,'CONFIRM','Y',90017)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {com.sicost.support.TestDatabaseDdl.drop(s,database);}
    }
    void sql(String text) {new JdbcTemplate(source).update(text);}
    String snapshot() {return new JdbcTemplate(source).queryForObject("SELECT json_build_object('payroll',(SELECT json_agg(s ORDER BY mth_cst_id) FROM cst.mth_cst s),'assignments',(SELECT json_agg(a ORDER BY asgn_id) FROM asgn.asgn a),'allocations',(SELECT json_agg(a ORDER BY cst_aloc_id) FROM cst.cst_aloc a),'expenses',(SELECT json_agg(e ORDER BY expns_id) FROM cst.drct_expns e),'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),'audit',(SELECT json_agg(h ORDER BY acs_hist_id) FROM sys.acs_hist h),'bills',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b))::text",String.class);}
    @Test void realProjectionReadbackSharedCostAndSimulationLeaveEverySourceUntouched() throws Exception {
        String before=snapshot();var q=service.query(Map.of("year","2026"));var detail=service.detail(90017,q);
        assertThat(detail.project().total_cost_amt()).isEqualTo(320);
        assertThat(detail.project().confirmed_supply_amt()).isEqualTo(200);
        assertThat(detail.project().budget_use_rt()).isEqualByComparingTo("80");
        assertThat(context.getBean(com.sicost.apiops.ProjectCostService.class).calculate(90017,"2026-10").cumulative_cost_amt()).isEqualTo(320);
        assertThat(detail.monthly_rows().stream().mapToLong(m->m.total_cost_amt()).sum()).isEqualTo(320);
        mvc.perform(post("/api/costs/projects/90017/simulate").contentType("application/json").content("{\"headcount_delta\":1,\"monthly_cost_amt\":20,\"months\":3}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.simulated").value(true));
        assertThat(snapshot()).isEqualTo(before);
        context.getBean(CostAllocationMapper.class).getClass();
        assertThat(service.detail(90017,q).project()).isEqualTo(detail.project());
    }
    @Test void filteredPopulationTotalAndFullSummaryAgreeWithTheActualRows() {
        var q=service.query(Map.of("year","2026","type","TK","status","RUN","q","분석"));
        var page=service.list(q);assertThat(page.total()).isEqualTo(1);assertThat(page.items()).hasSize(1);
        assertThat(page.summary().confirmed_supply_amt()).isEqualTo(200);
        assertThat(service.list(service.query(Map.of("year","2025"))).items()).extracting(Row::prj_id).containsExactly(90018L);
    }
    @Test void strictRevenueMissingDateDoesNotFallbackAndNegativeAmendmentOccursOnce() {
        var query=context.getBean(BillingRevenueQuery.class);
        assertThat(query.projectRevenue(90017,2026)).containsExactly(new BillingRevenueQuery.ProjectRevenue("2026-10",200,false));
        sql("UPDATE bill.bill SET bill_dt=NULL WHERE bill_id=90017");
        assertThat(query.projectRevenue(90017,2026)).contains(new BillingRevenueQuery.ProjectRevenue(null,210,true));
        assertThat(service.detail(90017,service.query(Map.of("year","2026"))).warnings()).contains("확정 청구일 누락");
    }
    @Test void closedSnapshotCannotFallbackToCurrentSalaryAndIsReadOnly() throws Exception {
        sql("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");
        mvc.perform(get("/api/costs/projects/90017").param("year","2026")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        sql("INSERT INTO cst.cst_aloc(cst_ym,dev_id,prj_id,asgn_id,aloc_ty_cd,mm_qty,lbr_cst_amt,drct_expns_amt) VALUES('2026-10',90017,90017,90017,'PROJECT',1,50,5)");
        String before=snapshot();var detail=service.detail(90017,service.query(Map.of("year","2026")));
        assertThat(detail.project().total_cost_amt()).isEqualTo(55);assertThat(snapshot()).isEqualTo(before);
    }
}
