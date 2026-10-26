package com.sicost.billstages;

import static com.sicost.billstages.ProjectBillStageModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class ProjectBillStagePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ProjectBillStageService service;
    ProjectBillStageMapper mapper;
    final LocalDate date = LocalDate.of(2026, 11, 1);

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/billstages/ProjectBillStageMapper.xml"));
        factory.afterPropertiesSet();
        var template = new SqlSessionTemplate(factory.getObject()); mapper = template.getMapper(ProjectBillStageMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, ProjectBillStageService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(ProjectBillStageMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.refresh(); service = context.getBean(ProjectBillStageService.class);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,cur_cntrct_amt) VALUES"
            + "(1,'P-2601','턴키','TK',500000000),(2,'P-2602','월단위','MO',500000000)");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (var admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }
    void sql(String sql, Object... args) { new org.springframework.jdbc.core.JdbcTemplate(source).update(sql, args); }
    long scalar(String sql) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            assertThat(r.next()).isTrue(); return r.getLong(1);
        }
    }
    String value(String sql, Object... args) {
        return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql, String.class, args);
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('stages',(SELECT json_agg(s ORDER BY bill_stg_id) FROM prj.bill_stg s),"
            + "'bills',(SELECT json_agg(b ORDER BY bill_id) FROM bill.bill b),"
            + "'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    StageInput input(String name, long amount) { return new StageInput(null, name, amount, date); }
    StageInput retain(Stage row) { return new StageInput(row.bill_stg_id(), row.bill_stg_nm(), row.sply_amt(), row.bill_plan_dt()); }
    List<Stage> three() {
        return service.save(1, List.of(input("착수금", 100000000), input("중도금", 200000000), input("잔금", 200000000))).items();
    }
    void bill(long id) throws Exception {
        sql("INSERT INTO bill.bill(bill_no,prj_id,bill_stg_id) VALUES('B-2611-01',1,?)", id);
    }
    @Test void materializesDatesVatBilledTotalsAndOneSummaryWithoutCreatingBills() throws Exception {
        three(); var result = service.list(1);
        assertThat(result.items()).extracting(Stage::stg_odr_no).containsExactly(1, 2, 3);
        assertThat(result.totalSupplyAmount()).isEqualTo(500000000);
        assertThat(result.cur_cntrct_amt()).isEqualTo(500000000L);
        assertThat(result.items().get(0).bill_plan_dt()).isEqualTo(date);
        assertThat(result.items().get(0).vat_amt()).isEqualTo(10000000);
        assertThat(scalar("SELECT count(*) FROM bill.bill")).isZero();
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.bill_stg' AND obj_rec_id='1' AND chg_user_id IS NULL")).isEqualTo(1);
        assertThat(value("SELECT aft_val FROM sys.data_chg_hist WHERE tbl_nm='prj.bill_stg'")).isEqualTo("청구 단계 3건 · 합계 500,000,000원");
        service.save(1, List.of(input("한 단계", 490000000)));
        assertThat(service.list(1).items()).hasSize(1);
        assertThat(service.list(1).totalSupplyAmount()).isEqualTo(490000000);
        assertThat(mapper.stages(2)).isEmpty();
    }
    @Test void reorderMutableAroundBilledIdentityIsCollisionSafe() throws Exception {
        var rows = three(); bill(rows.get(1).bill_stg_id());
        String original = value("SELECT row_to_json(s)::text FROM prj.bill_stg s WHERE bill_stg_id=?", rows.get(1).bill_stg_id());
        String billing = value("SELECT json_agg(b)::text FROM bill.bill b");
        service.save(1, List.of(retain(rows.get(2)), retain(rows.get(1)), retain(rows.get(0))));
        assertThat(service.list(1).items()).extracting(Stage::bill_stg_id)
            .containsExactly(rows.get(2).bill_stg_id(), rows.get(1).bill_stg_id(), rows.get(0).bill_stg_id());
        assertThat(service.list(1).items().get(1).billed()).isTrue();
        assertThat(value("SELECT row_to_json(s)::text FROM prj.bill_stg s WHERE bill_stg_id=?", rows.get(1).bill_stg_id())).isEqualTo(original);
        assertThat(value("SELECT json_agg(b)::text FROM bill.bill b")).isEqualTo(billing);
    }
    @ParameterizedTest @ValueSource(strings = {"name", "amount", "date", "delete", "order"})
    void billedRejectionPreservesStageBillingAndHistory(String kind) throws Exception {
        var rows = three(); var row = rows.get(0); bill(row.bill_stg_id()); String before = snapshot();
        var changed = switch (kind) {
            case "name" -> new StageInput(row.bill_stg_id(), "수정", row.sply_amt(), date);
            case "amount" -> new StageInput(row.bill_stg_id(), row.bill_stg_nm(), 1, date);
            case "date" -> new StageInput(row.bill_stg_id(), row.bill_stg_nm(), row.sply_amt(), date.plusDays(1));
            default -> retain(row);
        };
        List<StageInput> request = kind.equals("delete") ? List.of() : kind.equals("order")
            ? List.of(input("앞 단계", 1), changed) : List.of(changed);
        assertThatThrownBy(() -> service.save(1, request)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo("STAGE_BILLED"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void auditFailureRollsBackAllMutableReplacement() throws Exception {
        var rows = three(); String before = snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(() -> service.save(1, List.of(retain(rows.get(2)), input("신규", 1))))
            .isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void invalidInputPreservesOldRowsAndHistory() throws Exception {
        three(); String before = snapshot();
        assertThatThrownBy(() -> service.save(1, List.of(input("0원", 0)))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void disabledProjectRefusesMutationAndAllowsRead() throws Exception {
        three(); sql("UPDATE prj.prj SET use_yn='N' WHERE prj_id=1"); String before = snapshot();
        assertThatThrownBy(() -> service.save(1, List.of())).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.field()).isEqualTo("use_yn"));
        assertThat(service.list(1).items()).hasSize(3); assertThat(snapshot()).isEqualTo(before);
    }
    @Test void emptyReplacementDeletesOnlySelectedProjectAndHasSummary() throws Exception {
        three();
        sql("INSERT INTO prj.bill_stg(prj_id,stg_odr_no,bill_stg_nm,sply_amt) VALUES(2,1,'다른 프로젝트',1)");
        service.save(1, List.of()); assertThat(service.list(1).items()).isEmpty();
        assertThat(scalar("SELECT count(*) FROM prj.bill_stg WHERE prj_id=2")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.bill_stg'")).isEqualTo(2);
    }
}
