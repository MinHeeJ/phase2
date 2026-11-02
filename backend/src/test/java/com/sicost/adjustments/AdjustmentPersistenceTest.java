package com.sicost.adjustments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.profit.MonthlyProfitMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.adjustments.AdjustmentModels.*;

class AdjustmentPersistenceTest {
    @Configuration("adjustmentTestTransactions") @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    AdjustmentService service;
    AdjustmentMapper mapper;
    MonthlyProfitMapper profit;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/adjustments/AdjustmentMapper.xml"),
            new ClassPathResource("mappers/profit/MonthlyProfitMapper.xml"));factory.afterPropertiesSet();
        var session=new SqlSessionTemplate(factory.getObject());mapper=session.getMapper(AdjustmentMapper.class);
        profit=session.getMapper(MonthlyProfitMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,AdjustmentService.class,CurrentUser.class);
        context.registerBean(AdjustmentMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();service=context.getBean(AdjustmentService.class);
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    void sql(String sql) {new JdbcTemplate(source).execute(sql);}
    String value(String sql) {return new JdbcTemplate(source).queryForObject(sql,String.class);}
    Input input(long amount) {return new Input("2026-08","2026-10","REVENUE",amount,"전월 조정",null,null,null);}
    Query query(int page,int size) {return new Query(2026,"2026-10",page,size,(long)page*size);}
    String snapshot() {
        return value("SELECT json_build_object('adjustments',(SELECT json_agg(a ORDER BY adj_id) FROM cst.prvmn_adj a),"
            +"'months',(SELECT json_agg(m ORDER BY cls_ym) FROM fin.mth_cls m),"
            +"'changes',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),"
            +"'access',(SELECT json_agg(h ORDER BY acs_hist_id) FROM sys.acs_hist h))::text");
    }
    @Test void seededClosedAugustAllowsCreateAndCancelAndReplacementWithReadback() {
        String months=value("SELECT json_agg(m ORDER BY cls_ym)::text FROM fin.mth_cls m");
        Row original=service.create(input(100));
        assertThat(original.status()).isEqualTo("ACTIVE");assertThat(original.amount()).isEqualTo(100);
        assertThat(original.createdAt().getOffset()).isNotNull();
        assertThat(profit.adjustment("2026-10")).isEqualTo(100);
        assertThat(value("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='ADJUSTMENT_CREATE' AND user_id IS NULL")).isEqualTo("1");
        service.cancel(original.id(),"  정정  ");
        assertThat(mapper.row(original.id()).reason()).isEqualTo("전월 조정");
        assertThat(profit.adjustment("2026-10")).isZero();
        assertThat(value("SELECT cncl_rsn_desc FROM cst.prvmn_adj WHERE adj_id="+original.id())).isEqualTo("정정");
        assertThat(value("SELECT count(*) FROM cst.prvmn_adj WHERE orgnl_ym='2026-08' AND adj_stts_cd='ACTIVE'")).isEqualTo("0");
        Row next=service.create(new Input("2026-08","2026-10","REVENUE",70,"정상 금액",null,null,original.id()));
        assertThat(next.id()).isNotEqualTo(original.id());assertThat(next.originalAdjustmentId()).isEqualTo(original.id());
        assertThat(profit.adjustment("2026-10")).isEqualTo(70);
        assertThat(service.list(query(0,20)).items()).extracting(Row::status).containsExactly("ACTIVE","CANCELLED");
        assertThat(value("SELECT json_agg(m ORDER BY cls_ym)::text FROM fin.mth_cls m")).isEqualTo(months);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='cst.prvmn_adj'")).isEqualTo("3");
    }
    @Test void listCountFiltersApplyMonthAndUsesIdTieBreakWithCancelledRows() {
        var first=service.create(input(100));service.cancel(first.id(),"취소");service.create(input(70));service.create(input(30));
        sql("INSERT INTO cst.prvmn_adj(orgnl_ym,aply_ym,adj_se_cd,adj_amt,rsn_desc) VALUES('2026-07','2026-09','REVENUE',1,'다른 달'),('2025-07','2025-09','REVENUE',1,'다른 해')");
        var p0=service.list(query(0,2));var p1=service.list(query(1,2));
        assertThat(p0.total()).isEqualTo(3);assertThat(p1.total()).isEqualTo(3);
        assertThat(p0.items()).hasSize(2);assertThat(p1.items()).hasSize(1);
        assertThat(p1.items().get(0).status()).isEqualTo("CANCELLED");
        assertThat(service.list(new Query(2026,null,0,100,0)).total()).isEqualTo(4);
        assertThat(p0.items()).allMatch(r->r.applyMonth().equals("2026-10"));
    }
    @Test void closedCorrectionInsertsMinusHundredAndPlusSeventyWithoutChangingOriginal() {
        sql("INSERT INTO cst.prvmn_adj(orgnl_ym,aply_ym,adj_se_cd,adj_amt,rsn_desc) VALUES('2026-07','2026-09','REVENUE',100,'마감 원본')");
        long id=Long.parseLong(value("SELECT max(adj_id) FROM cst.prvmn_adj"));Row before=mapper.row(id);
        service.create(new Input("2026-07","2026-10","REVENUE",-100,"역방향",null,null,id));
        service.create(new Input("2026-07","2026-10","REVENUE",70,"정상",null,null,id));
        assertThat(mapper.row(id)).isEqualTo(before);assertThat(profit.adjustment("2026-10")).isEqualTo(-30);
        assertThat(profit.adjustment("2026-09")).isEqualTo(100);
    }
    @Test void signsAndEtcAreSeparatedAndCancelledDoesNotContribute() {
        service.create(input(100));
        service.create(new Input("2026-08","2026-10","COST",-20,"원가",null,null,null));
        service.create(new Input("2026-08","2026-10","SGA",-10,"판관비",null,null,null));
        var etc=service.create(new Input("2026-08","2026-10","ETC",9,"영업외",null,null,null));
        assertThat(profit.adjustment("2026-10")).isEqualTo(70);
        assertThat(profit.entries("2026-10")).anyMatch(e->e.category().equals("NOI")&&e.amount()==9);
        service.create(new Input("2026-08","2026-10","COST",20,"원가 역방향",null,null,null));
        service.create(new Input("2026-08","2026-10","SGA",10,"판관비 역방향",null,null,null));
        assertThat(profit.adjustment("2026-10")).isEqualTo(100);
        service.cancel(etc.id(),"취소");assertThat(profit.entries("2026-10")).noneMatch(e->e.name().equals("영업외"));
    }
    @ParameterizedTest @ValueSource(strings={"create","cancel"})
    void auditFailureRollsBackAllMutationAndAccess(String action) {
        Row row=service.create(input(100));String before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_adjustment_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->{if(action.equals("create"))service.create(input(70));else service.cancel(row.id(),"취소");}).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void closedMonthCancelAndDuplicateCancelAreImmutable() {
        Row row=service.create(input(100));service.cancel(row.id(),"취소");String before=snapshot();
        assertThatThrownBy(()->service.cancel(row.id(),"또 취소")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ALREADY_CANCELLED"));
        assertThat(snapshot()).isEqualTo(before);
        Row active=service.create(input(70));
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED')");before=snapshot();
        assertThatThrownBy(()->service.cancel(active.id(),"취소")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void concurrentCancellationHasExactlyOneWinner() throws Exception {
        Row row=service.create(input(100));var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<String> action=()->{gate.await();try {service.cancel(row.id(),"취소");return "OK";}catch(ApiException ex){return ex.code();}};
            var a=pool.submit(action);var b=pool.submit(action);gate.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","ALREADY_CANCELLED");
            assertThat(value("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='ADJUSTMENT_CANCEL'")).isEqualTo("1");
        } finally {pool.shutdownNow();}
    }
}
