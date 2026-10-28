package com.sicost.billings;

import static com.sicost.billings.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
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
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BillingPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingService service;
    BillingMapper mapper;
    MockMvc mvc;
    ObjectMapper json;
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
        factory.setMapperLocations(new ClassPathResource("mappers/billings/BillingMapper.xml"));
        factory.afterPropertiesSet(); mapper=new SqlSessionTemplate(factory.getObject()).getMapper(BillingMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,BillingService.class,BillingAmountService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(BillingMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
        context.refresh(); service=context.getBean(BillingService.class);
        json=context.getBean(ObjectMapper.class).copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc=MockMvcBuilders.standaloneSetup(new BillingController(service,json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(1,'P-BILL-TK','청구 시험 TK','TK','STAGE'),(2,'P-BILL-MO','청구 시험 MO','MO','MONTH'),(3,'P-BILL-DP','청구 시험 DP','DP','HOUR'),(4,'P-BILL-IN','청구 시험 IN','IN',NULL)");
        sql("INSERT INTO prj.bill_stg(bill_stg_id,prj_id,stg_odr_no,bill_stg_nm,sply_amt,bill_plan_dt) VALUES(1,1,1,'착수',10000,'2026-10-22'),(2,1,2,'잔금',20000,'2026-11-22'),(3,1,3,'미정',30000,NULL)");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'BILL-TEST','청구 시험 인력','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(1,'A-BILL-MO',2,1,'DEV','2026-10-01','2026-12-31'),(2,'A-BILL-DP',3,1,'DEV','2026-10-01','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(1,'2026-10',0.75),(2,'2026-10',1)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(1,'2026-10-01',10000),(2,'2026-10-01',200)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null) context.close();
        if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement()) { s.execute(text); }
    }
    String value(String text) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)) {
            r.next(); return r.getString(1);
        }
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('bill',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b),'detail',(SELECT json_agg(d ORDER BY bill_dtl_id) FROM bill.bill_dtl d),'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    BillingCreate createInput(String body) throws Exception { return BillingInput.create(json.readTree(body)); }
    BillingDetail create(String body) throws Exception { return service.create(createInput(body)); }
    BillingDetail update(long id,String body) throws Exception {
        var node=json.readTree(body); var fields=new HashSet<String>(); node.fieldNames().forEachRemaining(fields::add);
        return service.update(id,BillingInput.update(node),fields);
    }
    ListInput listInput(String type,String status,String q) {
        return new ListInput(2026,status,type,null,q,0,50,"bill_no,asc");
    }
    @Test void anonymousTkCreateReturnsGeneratedHeaderThenDetailAndAudit() throws Exception {
        mvc.perform(post("/api/billings").contentType(MediaType.APPLICATION_JSON).content("{\"project_id\":1,\"stage_id\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.bill_no").value("B-2610-01"))
            .andExpect(jsonPath("$.data.details[0].bill_amt").value(10000));
        assertThat(value("SELECT count(*) FROM bill.bill WHERE bill_stts_cd='DRAFT' AND created_by IS NULL AND tot_amt=11000")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM bill.bill_dtl d JOIN bill.bill b ON b.bill_id=d.bill_id")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='bill.bill' AND chg_user_id IS NULL")).isEqualTo("1");
    }
    @Test void monthlyCalculationReadsEffectivePriceAndMmWithoutWritingSources() throws Exception {
        var result=create("{\"project_id\":2,\"month\":\"2026-10\"}");
        assertThat(result.billing().sply_amt()).isEqualTo(7500);
        assertThat(result.details().get(0).mm_qty()).isEqualByComparingTo("0.75");
        assertThat(value("SELECT mm_qty FROM asgn.asgn_mth WHERE asgn_id=1")).isEqualTo("0.75");
    }
    @Test void dpHourWithoutDaysCalculatesFromHoursAndManualOvertime() throws Exception {
        sql("INSERT INTO bill.dspch_atnd(work_ym,prj_id,dev_id,asgn_id,rglr_tm_qty,mth_work_day_qty) VALUES('2026-10',3,1,2,8,20)");
        var result=create("{\"project_id\":3,\"month\":\"2026-10\",\"details\":[{\"asgn_id\":2,\"ovtm_amt\":55}]}");
        assertThat(result.details().get(0).mm_qty()).isNull();
        assertThat(result.billing().sply_amt()).isEqualTo(1655);
        assertThat(result.details().get(0).ovtm_amt()).isEqualTo(55);
    }
    @Test void listVirtualPlansHaveNullIdentityAndFilteredTotalMatchesRows() throws Exception {
        var page=service.list(listInput("TK","PLAN",null));
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).allMatch(row -> row.virtual_plan() && row.bill_id()==null && row.bill_no()==null);
        create("{\"project_id\":1,\"stage_id\":1}");
        page=service.list(listInput("TK","PLAN","TK"));
        assertThat(page.total()).isEqualTo(page.items().size()).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM bill.bill WHERE bill_stts_cd='PLAN'")).isEqualTo("0");
    }
    @Test void cancelledOriginalReleasesVirtualStageButKeepsNumberHistory() throws Exception {
        var first=create("{\"project_id\":1,\"stage_id\":1}");
        sql("UPDATE bill.bill SET bill_stts_cd='CANCEL' WHERE bill_id="+first.billing().bill_id());
        assertThat(service.list(listInput("TK","PLAN",null)).total()).isEqualTo(2);
        var next=create("{\"project_id\":1,\"stage_id\":1}");
        assertThat(next.billing().bill_no()).isEqualTo("B-2610-02");
    }
    @Test void exportUsesListFiltersAndVirtualRowsWithoutPagination() throws Exception {
        var filter = new BillingFilter(2026,"PLAN","TK",null,"TK",0,1,"sply_amt","desc");
        var listed = mapper.list(filter);
        var exported = mapper.export(filter);
        assertThat(listed).hasSize(1);
        assertThat(exported).hasSize(2).startsWith(listed.get(0));
        assertThat(exported).allMatch(row -> row.virtual_plan() && row.bill_id() == null);
        assertThat(exported.stream().map(BillingRow::sply_amt).toList())
            .containsExactly(20000L,10000L);
        assertThat(mapper.export(new BillingFilter(2026,"PLAN","TK",999L,"TK",0,1,"sply_amt","desc")))
            .isEmpty();
    }
    @Test void undatedStageCanRegisterWithoutInventingSelectedYearPlan() throws Exception {
        var result=create("{\"project_id\":1,\"stage_id\":3}");
        assertThat(result.billing().bill_dt()).isNull();
        assertThat(result.details().get(0).work_ym()).isEqualTo("2026-10");
    }
    @Test void ordinaryUpdatePreservesSnapshotsAndManualSupplyUntilExplicitRecalculation() throws Exception {
        var created=create("{\"project_id\":2,\"month\":\"2026-10\"}"); long id=created.billing().bill_id();
        sql("UPDATE asgn.untprc_hist SET bill_untprc_amt=20000 WHERE asgn_id=1");
        sql("UPDATE asgn.asgn_mth SET mm_qty=0.50 WHERE asgn_id=1");
        var result=update(id,"{\"sply_amt\":1234,\"vat_amt\":17,\"rmrk_desc\":\"수동 보존\"}");
        assertThat(result.billing().sply_amt()).isEqualTo(1234);
        assertThat(result.billing().vat_amt()).isEqualTo(17);
        assertThat(result.details()).isEqualTo(created.details());
        result=update(id,"{\"recalculate\":true,\"sply_amt\":1234}");
        assertThat(result.details().get(0).bill_amt()).isEqualTo(10000);
        assertThat(result.billing().sply_amt()).isEqualTo(1234);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE clmn_nm='sply_amt' AND bfr_val='7500' AND aft_val='1234'")).isEqualTo("1");
    }
    @Test void confirmedUpdateLeavesHeaderDetailsAndHistoryUntouched() throws Exception {
        var created=create("{\"project_id\":1,\"stage_id\":1}"); long id=created.billing().bill_id();
        sql("UPDATE bill.bill SET bill_stts_cd='CONFIRM',bill_dt='2026-10-22' WHERE bill_id="+id);
        String before=snapshot();
        assertThatThrownBy(()->update(id,"{\"sply_amt\":1,\"recalculate\":true}"))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("STALE_BILL"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void closedOldAndNewMonthRejectDateMoveAndPreserveDetail() throws Exception {
        var created=create("{\"project_id\":1,\"stage_id\":1}"); long id=created.billing().bill_id();
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        String before=snapshot();
        assertThatThrownBy(()->update(id,"{\"bill_dt\":\"2026-11-01\"}"))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void detailKeepsReceiptAliasOriginalLinkAndHistoryWithoutMultiplicativeJoins() throws Exception {
        var original=create("{\"project_id\":2,\"month\":\"2026-10\"}"); long id=original.billing().bill_id();
        sql("UPDATE bill.bill SET bill_stts_cd='CONFIRM' WHERE bill_id="+id);
        sql("INSERT INTO bill.rcpt(bill_id,rcpt_dt,rcpt_amt) VALUES("+id+",'2026-10-22',100),("+id+",'2026-11-02',200)");
        sql("INSERT INTO bill.bill(bill_no,prj_id,atrb_ym,sply_amt,vat_amt,bill_stts_cd,amnd_yn,orgnl_bill_id) VALUES('B-2610-99',2,'2026-10',-100,-10,'CONFIRM','Y',"+id+")");
        var result=service.detail(id);
        assertThat(result.receipts()).hasSize(2);
        assertThat(result.receipts().get(0).receipt_id()).isPositive();
        assertThat(result.amendments()).hasSize(1);
        assertThat(result.billing().received_amt()).isEqualTo(300);
        assertThat(result.billing().receivable_amt()).isEqualTo(7840);
        assertThat(result.change_history()).isNotEmpty();
        assertThat(result.billing().atrb_ym()).isEqualTo("2026-10");
    }
    @ParameterizedTest @ValueSource(strings={"bill.bill","bill.bill_dtl","sys.data_chg_hist"})
    void createFailureAtAnyWriteRollsBackHeaderDetailsAuditAndNumber(String table) throws Exception {
        String before=snapshot();
        sql("ALTER TABLE "+table+" ADD CONSTRAINT reject_billing_write CHECK(false) NOT VALID");
        assertThatThrownBy(()->create("{\"project_id\":1,\"stage_id\":1}")).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
        sql("ALTER TABLE "+table+" DROP CONSTRAINT reject_billing_write");
        assertThat(create("{\"project_id\":1,\"stage_id\":1}").billing().bill_no()).isEqualTo("B-2610-01");
    }
    @Test void updateAuditFailureRollsBackHeaderAndRecalculation() throws Exception {
        long id=create("{\"project_id\":2,\"month\":\"2026-10\"}").billing().bill_id();
        String before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_billing_write CHECK(false) NOT VALID");
        assertThatThrownBy(()->update(id,"{\"sply_amt\":123,\"recalculate\":true}")).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void concurrentDifferentStagesAllocateUniqueNumbersAndDuplicateStageLoses() throws Exception {
        var executor=Executors.newFixedThreadPool(3); var start=new CountDownLatch(1);
        try {
            var a=executor.submit(()->{start.await(); return create("{\"project_id\":1,\"stage_id\":1}");});
            var b=executor.submit(()->{start.await(); return create("{\"project_id\":1,\"stage_id\":2}");});
            start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS).billing().bill_no())
                .isNotEqualTo(b.get(10,TimeUnit.SECONDS).billing().bill_no());
            assertThatThrownBy(()->create("{\"project_id\":1,\"stage_id\":1}"))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ALREADY_BILLED"));
            assertThat(value("SELECT count(DISTINCT bill_no) FROM bill.bill")).isEqualTo("2");
        } finally { executor.shutdownNow(); }
    }
}
