package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.*;
import com.sicost.common.billing.BillingSupportModels.Period;
import com.sicost.common.persistence.*;
import com.sicost.common.security.CurrentUser;
import com.sicost.shared.SharedReadMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

class BillingPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BillingRevenueQuery query;
    BillingMutationService mutations;
    BillingMutationMapper mapper;
    TransactionTemplate transaction;

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(
            new ClassPathResource("mappers/common/BillingRevenueQuery.xml"),
            new ClassPathResource("mappers/common/BillingMutationMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"),
            new ClassPathResource("mappers/shared/SharedReadMapper.xml"));
        factory.afterPropertiesSet();
        var template = new SqlSessionTemplate(factory.getObject());
        query = template.getMapper(BillingRevenueQuery.class);
        mapper = template.getMapper(BillingMutationMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, BillingMutationService.class, AssignmentConcurrencyService.class, CurrentUser.class);
        context.registerBean(BillingMutationMapper.class, () -> mapper);
        context.registerBean(AssignmentConcurrencyMapper.class, () -> template.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(SharedReadMapper.class, () -> template.getMapper(SharedReadMapper.class));
        context.registerBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-31T15:00:00Z"), ZoneOffset.UTC));
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.refresh();
        mutations = context.getBean(BillingMutationService.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd) VALUES(1,'P-2601','청구 검증','TK'),(2,'P-2602','월 검증','MO'),(3,'P-2603','파견 검증','DP')");
    }
    @AfterEach void removeDatabase() throws Exception {
        if (context != null) context.close();
        if (source == null) return;
        try (var c = DriverManager.getConnection(adminUrl, user, password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s, database);
        }
    }
    void sql(String text) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(text); }
    }
    String value(String text) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(text)) {
            r.next(); return r.getString(1);
        }
    }
    void bill(long id, int project, String state, long supply) throws Exception {
        try (var c = source.getConnection(); var s = c.prepareStatement("INSERT INTO bill.bill(bill_id,bill_no,prj_id,atrb_ym,bill_dt,bill_stts_cd,sply_amt,vat_amt) VALUES(?,?,?,'2026-10','2026-11-01',?,?,10)")) {
            s.setLong(1, id); s.setString(2, "B-2610-" + id); s.setInt(3, project);
            s.setString(4, state); s.setLong(5, supply); s.executeUpdate();
        }
    }
    @Test void allStatesPersistButOnlyConfirmedSupplyAccruesAndReceiptsDoNotMoveRevenue() throws Exception {
        int id = 0;
        for (String state : List.of("PLAN", "DRAFT", "CONFIRM", "CANCEL")) bill(++id, 1, state, 100);
        bill(5, 2, "CONFIRM", 200); bill(6, 3, "CONFIRM", 300);
        var period = new Period("2026-10", "2026-11");
        var before = query.monthlyRevenue(period);
        assertThat(before).containsExactly(new Revenue("2026-10", 500), new Revenue("2026-11", 100));
        assertThat(query.confirmedSupply(new ProjectMonth(1, "2026-11"))).isEqualTo(new Supply(100, 1));
        sql("INSERT INTO bill.rcpt(bill_id,rcpt_dt,rcpt_amt) VALUES(3,'2026-12-01',40),(3,'2026-12-02',20)");
        assertThat(query.monthlyRevenue(period)).isEqualTo(before);
        assertThat(query.balance(3).receivable()).isEqualTo(50);
        assertThat(query.cashReceipts(new Period("2026-12", "2026-12")))
            .extracting(CashReceipt::rcpt_amt).containsExactly(40L, 20L);
        assertThat(value("SELECT count(DISTINCT bill_stts_cd) FROM bill.bill")).isEqualTo("4");
    }
    @Test void onlyConfirmedAmendmentsAdjustVatIncludedReceivables() throws Exception {
        bill(1, 2, "CONFIRM", 1000);
        sql("INSERT INTO bill.bill(bill_no,prj_id,atrb_ym,bill_stts_cd,sply_amt,vat_amt,amnd_yn,orgnl_bill_id) VALUES('B-2610-10',2,'2026-10','CONFIRM',-100,-10,'Y',1),('B-2610-11',2,'2026-10','DRAFT',-200,-20,'Y',1),('B-2610-12',2,'2026-10','CANCEL',-300,-30,'Y',1)");
        assertThat(query.balance(1).adjustedTotal()).isEqualTo(900);
        assertThat(query.confirmedSupply(new ProjectMonth(2, "2026-10"))).isEqualTo(new Supply(900, 2));
    }
    @Test void auditFailureRollsBackBusinessWriteAndSuccessfulAuditHasNullActor() throws Exception {
        bill(1, 2, "DRAFT", 100);
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(() -> transaction.execute(status -> {
            mutations.lock(1);
            try {
                var connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source);
                try (var s = connection.createStatement()) { s.executeUpdate("UPDATE bill.bill SET sply_amt=200 WHERE bill_id=1"); }
            } catch (SQLException e) { throw new IllegalStateException(e); }
            mutations.audit(new Audit("bill.bill", "1", "UPDATE", "sply_amt", "100", "200", "정정", null));
            return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(value("SELECT sply_amt FROM bill.bill WHERE bill_id=1")).isEqualTo("100");
        sql("ALTER TABLE sys.data_chg_hist DROP CONSTRAINT reject_history");
        transaction.execute(status -> {
            mutations.audit(new Audit("bill.bill", "1", "UPDATE", "sply_amt", "100", "100", "확인", null));
            return null;
        });
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE chg_user_id IS NULL AND tbl_nm='bill.bill' AND obj_rec_id='1'")).isEqualTo("1");
        assertThat(value("SELECT cntrct_ty_cd FROM prj.prj WHERE prj_id=2")).isEqualTo("MO");
        assertThat(value("SELECT count(*) FROM bill.dun_hist")).isEqualTo("0");
    }
    @Test void concurrentNumbersAreUniqueAndPersistUnderTheSameAdvisoryLock() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<String> allocate = () -> {
            start.await();
            return transaction.execute(status -> {
                String number = mutations.nextNumber();
                var connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source);
                try (var s = connection.prepareStatement("INSERT INTO bill.bill(bill_no,prj_id) VALUES(?,1)")) {
                    s.setString(1, number); s.executeUpdate();
                } catch (SQLException e) { throw new IllegalStateException(e); }
                return number;
            });
        };
        try {
            var first = executor.submit(allocate); var second = executor.submit(allocate); start.countDown();
            assertThat(Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("B-2611-01", "B-2611-02");
            assertThat(value("SELECT count(DISTINCT bill_no) FROM bill.bill")).isEqualTo("2");
        } finally { executor.shutdownNow(); }
    }
}
