package com.sicost.closingops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.apiops.*;
import com.sicost.billingsops.BillingGapService;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.costallocation.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.sicost.closingops.ClosingModels.*;

class ClosingPersistenceTest {
    @Configuration("closingCommandTransactions") @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ClosingService service;
    ClosingMapper mapper;
    ClosingQueryMapper query;
    ClosingSnapshotService components;
    JdbcTemplate jdbc;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {com.sicost.support.TestDatabaseDdl.create(statement,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/profit/MonthlyProfitMapper.xml"),new ClassPathResource("mappers/closingops/ClosingMapper.xml"),new ClassPathResource("mappers/apiops/ClosingQueryMapper.xml"));factory.afterPropertiesSet();
        var session=new SqlSessionTemplate(factory.getObject());mapper=session.getMapper(ClosingMapper.class);query=session.getMapper(ClosingQueryMapper.class);
        var costs=mock(CostAllocationService.class);when(costs.calculate(anyString(),isNull())).thenAnswer(i->new CostAllocationModels.Allocation(i.getArgument(0),"2026-12",false,"원",List.of(),List.of(),0));
        var gaps=mock(BillingGapService.class);when(gaps.filtered(any())).thenReturn(List.of());
        components=mock(ClosingSnapshotService.class);when(components.components(anyString())).thenAnswer(i->components(i.getArgument(0)));
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,ClosingService.class,ClosingCheckService.class,ClosingCheckWriter.class,ClosingHistoryService.class,ClosingQueryService.class,CurrentUser.class);
        context.registerBean(ClosingMapper.class,()->mapper);context.registerBean(ClosingQueryMapper.class,()->query);
        context.registerBean(CostAllocationService.class,()->costs);context.registerBean(BillingGapService.class,()->gaps);context.registerBean(ClosingSnapshotService.class,()->components);
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-12-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.refresh();service=context.getBean(ClosingService.class);jdbc=new JdbcTemplate(source);
        sql("INSERT INTO mst.bsn_part(bsn_part_id,bsn_part_nm,hq_yn) VALUES(95901,'마감 검증 본사','Y')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,bsn_part_id,hire_dt) VALUES(95901,'CLOSE-95901','마감 검증','FT',95901,'2026-10-01')");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(95901,'2026-10','FT',100),(95901,'2026-11','FT',100)");
        sql("INSERT INTO fin.corp_tax_plan(tax_yr,annl_expc_amt) VALUES('2026',0) ON CONFLICT(tax_yr) DO NOTHING");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){com.sicost.support.TestDatabaseDdl.drop(statement,database);}
    }
    void sql(String s){jdbc.execute(s);}
    String value(String s){return jdbc.queryForObject(s,String.class);}
    Components components(String month) {
        var profit=new LinkedHashMap<String,Object>();for(String key:List.of("revenue","project_labor_cost","idle_cost","direct_expenses","sga_input","indirect_labor_cost","rnd_cost","previous_adjustment","non_operating_income","non_operating_expenses","allocation_revenue_reference"))profit.put(key,0L);
        profit.put("idle_cost",100L);
        var row=new CostAllocationModels.AllocationRow(month,95901L,null,null,"IDLE",BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE,100,0,0,null,"N","N","N");
        return new Components(month,List.of(row),profit,List.of(),0,1000,900,List.of());
    }
    Map<String,String> outputs() {
        var result=new LinkedHashMap<String,String>();
        for(String table:List.of("cst.cst_aloc","fin.mth_pl","fin.mth_pl_dtl","fin.corp_tax_mth","fin.cash_bal","fin.mth_cls_hist","sys.acs_hist","sys.data_chg_hist"))result.put(table,value("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+table+" t"));
        return result;
    }
    @Test void twoChecksKeepNineLatestRowsAndTwoImmutableRunsWithUnchangedPayroll() {
        String payroll=value("SELECT jsonb_agg(to_jsonb(c))::text FROM cst.mth_cst c");
        service.check("2026-10");service.check("2026-10");
        assertThat(value("SELECT count(*) FROM fin.mth_cls_chk WHERE cls_ym='2026-10'")).isEqualTo("9");
        assertThat(value("SELECT count(*) FROM fin.mth_cls_chk_hist WHERE cls_ym='2026-10'")).isEqualTo("2");
        assertThat(value("SELECT jsonb_agg(to_jsonb(c))::text FROM cst.mth_cst c")).isEqualTo(payroll);
    }
    @Test void unassignedMissingSalaryIsFailAndDisabledHistoricalEmployeeRemainsTarget() {
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,hire_dt,rsgn_dt,use_yn) VALUES(95902,'CLOSE-95902','과거 재직','FT','2026-01-01','2026-11-01','N')");
        assertThat(service.check("2026-10").checks()).anyMatch(c->c.code().equals("SALARY_UPLOAD")&&c.status().equals("FAIL"));
    }
    @Test void genuineZeroTargetsIsSalaryOk() {
        assertThat(mapper.sourceChecks("2025-01")).anyMatch(c->c.code().equals("SALARY_UPLOAD")&&c.status().equals("OK"));
    }
    @Test void mmOneIsOkAndOnePointZeroOneWarn() {
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd) VALUES(95901,'P-2695901','마감 MM 검증','MO')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(95901,'A-95901',95901,95901,'BE','2026-10-01','2026-10-31'),(95902,'A-95902',95901,95901,'BE','2026-10-01','2026-10-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(95901,'2026-10',0.50),(95902,'2026-10',0.50)");
        assertThat(mapper.sourceChecks("2026-10")).anyMatch(c->c.code().equals("MM_OVER")&&c.status().equals("OK"));
        sql("UPDATE asgn.asgn_mth SET mm_qty=0.51 WHERE asgn_id=95902");
        assertThat(mapper.sourceChecks("2026-10")).anyMatch(c->c.code().equals("MM_OVER")&&c.status().equals("WARN")&&c.count()==1);
    }
    @Test void failRefusesCloseButCommitsLatestChecksAndArchive() {
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,hire_dt) VALUES(95902,'CLOSE-95902','누락','FT','2026-10-01')");
        var before=outputs();assertThatThrownBy(()->service.confirm("2026-10",true)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSE_CHECK_FAILED"));
        assertThat(outputs()).isEqualTo(before);assertThat(value("SELECT count(*) FROM fin.mth_cls_chk_hist WHERE cls_ym='2026-10'")).isEqualTo("1");
    }
    @Test void warningWithoutAckPreservesChecksButNotClose() {
        assertThatThrownBy(()->service.confirm("2026-10",false)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSE_CHECK_FAILED"));
        assertThat(value("SELECT count(*) FROM fin.mth_cls_chk_hist WHERE cls_ym='2026-10'")).isEqualTo("1");assertThat(mapper.profitSource("2026-10")).isNull();
    }
    @Test void snapshotManifestAcceptsEmptyDetailsAndStoredValuesAreImmutable() {
        service.confirm("2026-10",true);var preview=mapper.preview("2026-10");
        assertThat(preview.cost()).isEqualTo(100);assertThat(preview.profit()).isEqualTo(-100);assertThat(preview.cash()).isEqualTo(900);
        sql("UPDATE cst.mth_cst SET slry_amt=99999 WHERE dev_id=95901");
        assertThat(mapper.preview("2026-10")).isEqualTo(preview);assertThat(context.getBean(ClosingQueryService.class).getClosing("2026-10").status()).isEqualTo("CLOSED");
    }
    @Test void concurrentConfirmHasExactlyOneWinner() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        Callable<String> action=()->{start.await();try{service.confirm("2026-10",true);return "CLOSED";}catch(ApiException ex){return ex.code();}};
        try {var a=pool.submit(action);var b=pool.submit(action);start.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("CLOSED","ALREADY_CLOSED");}
        finally {pool.shutdownNow();}
        assertThat(value("SELECT count(*) FROM fin.mth_cls_hist WHERE cls_ym='2026-10'")).isEqualTo("1");
    }
    @Test void sourceSharedGuardWaitsUntilClosingTransactionEnds() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        when(components.components("2026-10")).thenAnswer(i->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return components("2026-10");});
        try {
            var closing=pool.submit(()->service.confirm("2026-10",true));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var sourceWrite=pool.submit(()->{try(var connection=source.getConnection();var statement=connection.createStatement()){connection.setAutoCommit(false);statement.execute("SET LOCAL lock_timeout='200ms'");statement.execute("LOCK TABLE fin.mth_cls IN SHARE MODE");connection.rollback();return "unexpected";}catch(SQLException ex){return ex.getSQLState();}});
            assertThat(sourceWrite.get(5,TimeUnit.SECONDS)).isEqualTo("55P03");release.countDown();assertThat(closing.get(10,TimeUnit.SECONDS).status()).isEqualTo("CLOSED");
        } finally {release.countDown();pool.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(strings={"fin.mth_pl","fin.corp_tax_mth","sys.data_chg_hist","sys.acs_hist"})
    void midCloseFailureRollsBackEveryCloseOutputAndKeepsIndependentChecks(String table) {
        var before=outputs();sql("ALTER TABLE "+table+" ADD CONSTRAINT reject_closing_output CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.confirm("2026-10",true)).isInstanceOf(RuntimeException.class);
        assertThat(outputs()).isEqualTo(before);assertThat(mapper.status("2026-10")).isEqualTo("OPEN");
        assertThat(value("SELECT count(*) FROM fin.mth_cls_chk_hist WHERE cls_ym='2026-10'")).isEqualTo("1");
    }
    @Test void previousMonthCannotReopen() {
        service.confirm("2026-10",true);service.confirm("2026-11",true);var before=outputs();
        assertThatThrownBy(()->service.reopen("2026-10","정정")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSE_ORDER"));assertThat(outputs()).isEqualTo(before);
    }
    @Test void activeReferenceBlocksButCancelledOnlyAllowsAndKeepsSourceImportAndHistory() {
        service.confirm("2026-10",true);
        sql("INSERT INTO fin.mth_pl(pl_ym,pl_src_cd) VALUES('2025-12','IMPORT')");
        sql("INSERT INTO cst.prvmn_adj(aply_ym,orgnl_ym,adj_se_cd,adj_amt,rsn_desc) VALUES('2026-11','2026-10','REVENUE',100,'조정')");
        var before=outputs();assertThatThrownBy(()->service.reopen("2026-10","정정")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ADJUSTMENT_REFERENCE_EXISTS"));assertThat(outputs()).isEqualTo(before);
        sql("UPDATE cst.prvmn_adj SET adj_stts_cd='CANCELLED',cncl_rsn_desc='취소',cncl_at=CURRENT_TIMESTAMP WHERE orgnl_ym='2026-10'");
        service.reopen("2026-10","정정");assertThat(mapper.status("2026-10")).isEqualTo("OPEN");assertThat(mapper.profitSource("2025-12")).isEqualTo("IMPORT");
        assertThat(value("SELECT count(*) FROM cst.mth_cst WHERE dev_id=95901")).isEqualTo("2");assertThat(value("SELECT count(*) FROM fin.mth_cls_hist WHERE cls_ym='2026-10'")).isEqualTo("2");
        assertThat(value("SELECT end_bal_amt IS NULL FROM fin.cash_bal WHERE bal_ym='2026-10'")).isEqualTo("t");
        service.confirm("2026-10",true);assertThat(value("SELECT count(*) FROM fin.mth_cls_hist WHERE cls_ym='2026-10'")).isEqualTo("3");
    }
    @Test void reverseClosedAdjustmentDoesNotCancelActiveOriginalReference() {
        service.confirm("2026-10",true);
        sql("INSERT INTO cst.prvmn_adj(adj_id,aply_ym,orgnl_ym,adj_se_cd,adj_amt,rsn_desc) VALUES(95901,'2026-11','2026-10','REVENUE',100,'원본')");
        service.confirm("2026-11",true);
        sql("INSERT INTO cst.prvmn_adj(aply_ym,orgnl_ym,adj_se_cd,adj_amt,rsn_desc,orgnl_adj_id) VALUES('2026-12','2026-10','REVENUE',-100,'역방향',95901)");
        assertThat(query.activeReferences("2026-10")).isEqualTo(2);assertThat(value("SELECT adj_stts_cd FROM cst.prvmn_adj WHERE adj_id=95901")).isEqualTo("ACTIVE");
    }
    @Test void reopenAuditFailureRestoresAllOutputsAndState() {
        service.confirm("2026-10",true);var before=outputs();sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_reopen CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.reopen("2026-10","정정")).isInstanceOf(RuntimeException.class);assertThat(outputs()).isEqualTo(before);assertThat(mapper.status("2026-10")).isEqualTo("CLOSED");
    }
    @Test void historyTimestampTiesUseNumericIdDescendingAndCountMatchesYear() {
        service.confirm("2026-10",true);service.reopen("2026-10","정정");
        var result=context.getBean(ClosingHistoryService.class).list(2026,0,1);assertThat(result.total()).isEqualTo(2);assertThat(result.items()).extracting(History::action).containsExactly("REOPEN");
        assertThat(context.getBean(ClosingHistoryService.class).list(2025,0,3).items()).isEmpty();
    }
}
