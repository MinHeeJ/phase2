package com.sicost.billings;

import static com.sicost.billings.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiException;
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

class BillingPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingService service;
    BillingMapper mapper;
    com.sicost.billingsops.BillingMapper operationsMapper;
    ObjectMapper json;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl,user,password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/billings/BillingMapper.xml"),
            new ClassPathResource("mappers/billingsops/BillingMapper.xml"));
        factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(BillingMapper.class);
        operationsMapper = new SqlSessionTemplate(factory.getObject())
            .getMapper(com.sicost.billingsops.BillingMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class,BillingService.class,BillingAmountService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(BillingMapper.class,() -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,() -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,() -> Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.registerBean(ObjectMapper.class,() -> new ObjectMapper().findAndRegisterModules());
        context.refresh(); service = context.getBean(BillingService.class); json = context.getBean(ObjectMapper.class);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,stlm_mthd_cd) VALUES(1,'P-BILL-TK','청구 시험','TK',NULL),(2,'P-BILL-MO','월 청구','MO','MONTH')");
        sql("INSERT INTO prj.bill_stg(bill_stg_id,prj_id,stg_odr_no,bill_stg_nm,sply_amt,bill_plan_dt) VALUES(7,1,1,'중도금',1000,'2026-10-01'),(8,1,2,'잔금',2000,'2026-11-01')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'K-BILL-1','인력','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(1,'A-BILL-1',2,1,'DEV','2026-10-15','2026-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(1,'2026-10',0.50)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(1,'2026-10-15',1001),(1,'2026-10-20',9000)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl,user,password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);} }
    String value(String text) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}
    }
    JsonNode body(String text) throws Exception { return json.readTree(text); }
    Detail tk(long stage) throws Exception { return service.create(body("{\"project_id\":1,\"stage_id\":"+stage+"}")); }
    Detail monthly() throws Exception { return service.create(body("{\"project_id\":2,\"month\":\"2026-10\"}")); }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('b',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b),'d',(SELECT json_agg(d ORDER BY bill_dtl_id) FROM bill.bill_dtl d),'h',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    @Test void virtualPlanReadWritesNothingAndFiltersBeforeCount() throws Exception {
        var page = service.list(Map.of("year","2026","status","PLAN","q","청구 시험","size","20"));
        assertThat(page.total()).isEqualTo(2); assertThat(page.items()).hasSize(2);
        assertThat(page.items()).allMatch(r -> r.virtual_plan() && r.bill_id() == null && r.bill_no() == null);
        assertThat(value("SELECT count(*) FROM bill.bill")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo("0");
    }
    @Test void tkGeneratedTotalAndDetailsSurviveServiceReload() throws Exception {
        var created = tk(7); long id = created.billing().bill_id();
        assertThat(created.billing().bill_no()).isEqualTo("B-2610-01");
        assertThat(value("SELECT tot_amt FROM bill.bill WHERE bill_id="+id)).isEqualTo("1100");
        assertThat(service.detail(id).details()).hasSize(1);
        assertThat(service.list(Map.of("year","2026","status","DRAFT")).total()).isEqualTo(1);
    }
    @Test void monthUsesInitialMidmonthRateNotLaterRateAndOrdinaryPutKeepsSnapshot() throws Exception {
        var created = monthly(); long id = created.billing().bill_id();
        assertThat(created.details().get(0).bill_amt()).isEqualTo(500);
        sql("UPDATE asgn.untprc_hist SET bill_untprc_amt=4000 WHERE asgn_id=1 AND aply_bgn_dt='2026-10-15'");
        sql("UPDATE asgn.asgn_mth SET mm_qty=0.75 WHERE asgn_id=1 AND work_ym='2026-10'");
        var updated = service.update(id,body("{\"txinv_no\":\"INV\",\"sply_amt\":450}"));
        assertThat(updated.details()).isEqualTo(created.details()); assertThat(updated.billing().sply_amt()).isEqualTo(450);
        var recalculated = service.update(id,body("{\"recalculate\":true}"));
        assertThat(recalculated.billing().sply_amt()).isEqualTo(3000);
        assertThat(recalculated.details().get(0).mm_qty()).isEqualByComparingTo("0.75");
    }
    @Test void normalAndAmendmentNumbersShareSequenceWithoutTruncatingAtOneHundred() throws Exception {
        var first = tk(7);
        assertThat(operationsMapper.nextNumber("2026-10")).isEqualTo("B-2610-02");
        sql("UPDATE bill.bill SET bill_no='B-2610-99' WHERE bill_id=" + first.billing().bill_id());
        assertThat(operationsMapper.nextNumber("2026-10")).isEqualTo("B-2610-100");
        assertThat(tk(8).billing().bill_no()).isEqualTo("B-2610-100");
        assertThat(operationsMapper.nextNumber("2026-10")).isEqualTo("B-2610-101");
    }
    @Test void bothDetailReadersIncludeReceiptAuditAfterReceiptDeletion() throws Exception {
        long id = tk(7).billing().bill_id();
        sql("INSERT INTO sys.data_chg_hist(tbl_nm,obj_rec_id,data_chg_ty_cd,bfr_val) "
            + "VALUES('bill.rcpt','91','DELETE','{\"bill_id\":" + id + ",\"rcpt_amt\":30}')");
        assertThat(service.detail(id).change_history())
            .anyMatch(h -> h.description().contains("rcpt_amt"));
        assertThat(operationsMapper.history(id))
            .anyMatch(h -> h.description().contains("rcpt_amt"));
    }
    @Test void dateMoveKeepsHeaderEffectiveMonthAndDetailInAgreement() throws Exception {
        var id=tk(7).billing().bill_id();
        service.update(id,body("{\"bill_dt\":\"2026-12-15\"}"));
        assertThat(value("SELECT work_ym FROM bill.bill_dtl WHERE bill_id="+id)).isEqualTo("2026-12");
        assertThat(service.detail(id).billing().bill_dt()).isEqualTo(LocalDate.of(2026,12,15));
    }
    @ParameterizedTest @ValueSource(strings={"CONFIRM","PLAN","CANCEL"})
    void nonDraftRejectsUpdateAndPreservesRows(String status) throws Exception {
        var id=tk(7).billing().bill_id(); sql("UPDATE bill.bill SET bill_stts_cd='"+status+"' WHERE bill_id="+id);
        var before=snapshot();
        assertThatThrownBy(() -> service.update(id,body("{\"sply_amt\":200}")))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("STALE_BILL"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"CREATE_AUDIT","CREATE_DETAIL","UPDATE_AUDIT","RECALCULATE_DETAIL"})
    void failureRollsBackHeaderDetailAuditAndNumberAllocation(String mode) throws Exception {
        Long id=mode.startsWith("CREATE")?null:tk(7).billing().bill_id();
        String before=snapshot();
        sql(mode.endsWith("AUDIT")
            ? "ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID"
            : "ALTER TABLE bill.bill_dtl ADD CONSTRAINT reject_detail CHECK(false) NOT VALID");
        assertThatThrownBy(() -> {
            if(id==null) tk(7); else service.update(id,body("{\"recalculate\":true,\"sply_amt\":300}"));
        }).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
        if(id==null) {
            sql(mode.endsWith("AUDIT")?"ALTER TABLE sys.data_chg_hist DROP CONSTRAINT reject_history"
                :"ALTER TABLE bill.bill_dtl DROP CONSTRAINT reject_detail");
            assertThat(tk(7).billing().bill_no()).isEqualTo("B-2610-01");
        }
    }
    @Test void concurrentNumberGenerationIsUniqueAndStageDuplicationIsRejected() throws Exception {
        var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var a=executor.submit(() -> {start.await(); return tk(7);});
            var b=executor.submit(() -> {start.await(); return tk(8);}); start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS).billing().bill_no()).isNotEqualTo(b.get(10,TimeUnit.SECONDS).billing().bill_no());
            assertThatThrownBy(() -> tk(7)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("ALREADY_BILLED"));
        } finally {executor.shutdownNow();}
    }
    @Test void cancelledStageReappearsVirtuallyAndReplacementKeepsHistoricalReference() throws Exception {
        var id=tk(7).billing().bill_id(); sql("UPDATE bill.bill SET bill_stts_cd='CANCEL' WHERE bill_id="+id);
        assertThat(service.list(Map.of("year","2026","status","PLAN")).total()).isEqualTo(2);
        assertThat(tk(7).billing().bill_id()).isNotEqualTo(id);
        assertThat(value("SELECT bill_stg_id FROM bill.bill WHERE bill_id="+id)).isEqualTo("7");
    }
    @Test void detailIncludesSeparateAmendmentsReceiptsAndAuditWithoutJoinMultiplication() throws Exception {
        var id=tk(7).billing().bill_id();
        sql("UPDATE bill.bill SET bill_stts_cd='CONFIRM' WHERE bill_id="+id);
        sql("INSERT INTO bill.bill(bill_no,prj_id,sply_amt,vat_amt,bill_stts_cd,amnd_yn,orgnl_bill_id) VALUES('B-2610-201',1,-100,-10,'CONFIRM','Y',"+id+"),('B-2610-202',1,-500,-50,'DRAFT','Y',"+id+")");
        sql("INSERT INTO bill.rcpt(bill_id,rcpt_dt,rcpt_amt) VALUES("+id+",'2026-11-01',100),("+id+",'2026-11-02',200)");
        var detail=service.detail(id);
        assertThat(detail.receipts()).hasSize(2); assertThat(detail.amendments()).hasSize(2);
        assertThat(detail.change_history()).isNotEmpty();
        assertThat(detail.billing().adjusted_total_amt()).isEqualTo(990);
        assertThat(detail.billing().received_amt()).isEqualTo(300);
        assertThat(detail.billing().receivable_amt()).isEqualTo(690);
        assertThat(detail.billing().bill_dt()).isNull();
    }
}
