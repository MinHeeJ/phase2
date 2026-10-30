package com.sicost.projects;

import com.sicost.billingsops.*;
import com.sicost.common.security.*;
import com.sicost.costallocation.*;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.projects.CostProjectModels.*;

class CostProjectPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    CostProjectMapper mapper;
    CostProjectService service;
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try (Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mappers/**/*.xml"));
        factory.afterPropertiesSet();
        var session=new SqlSessionTemplate(factory.getObject());
        mapper=session.getMapper(CostProjectMapper.class);
        var billing=session.getMapper(BillingMapper.class);
        var clock=Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));
        var permissions=new PermissionService();
        service=new CostProjectService(mapper,new CostAllocationService(session.getMapper(CostAllocationMapper.class),clock),
            new BillingRevenueQuery(billing,new BillingReceivableService(billing,clock,permissions),clock,permissions),permissions,clock);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cntrct_bgn_dt,cntrct_end_dt,cur_end_dt,cur_cntrct_amt,trgt_cst_amt,trgt_mrgn_rt) VALUES(901,'P-26901','원가 테스트','MO','RUN','2026-10-01','2026-10-31','2026-10-31',500,400,20)");
        sql("INSERT INTO cst.drct_expns(expns_ym,prj_id,expns_item_cd,expns_amt) VALUES('2026-10',901,'PEXP',320)");
        sql("INSERT INTO bill.bill(bill_no,prj_id,atrb_ym,bill_dt,sply_amt,vat_amt,bill_stts_cd) VALUES('B-2610-91',901,'2026-10','2026-10-01',200,0,'CONFIRM')");
    }
    @AfterEach void cleanup() throws Exception {
        if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    void sql(String sql) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);} }
    String value(String sql) { return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql,String.class); }
    @Test void queryMaterializesRealSchemaAndSimulationLeavesAllSourceRowsUnchanged() {
        var detail=service.detail(901,2026);
        assertThat(detail.project().cumulative_cost_amt()).isEqualTo(320);
        assertThat(detail.project().budget_use_rt()).isEqualByComparingTo("80");
        assertThat(detail.project().confirmed_supply_amt()).isEqualTo(200);
        String before=value("SELECT json_agg(p)::text FROM prj.prj p");
        service.simulate(901,new SimulationInput(1,20,2,30L));
        assertThat(value("SELECT json_agg(p)::text FROM prj.prj p")).isEqualTo(before);
        assertThat(value("SELECT count(*) FROM cst.cst_aloc")).isEqualTo("0");
        assertThat(mapper.source(901).trgt_cst_amt()).isEqualTo(400);
    }
    @Test void closedMonthWithExpectedExpenseAndNoSnapshotRejectsRead() throws Exception {
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'");
        assertThatThrownBy(()->service.detail(901,2026)).isInstanceOf(com.sicost.common.api.ApiException.class)
            .hasMessageContaining("마감 배분자료 없음");
    }
}
