package com.sicost.businessplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;
import static com.sicost.businessplan.BusinessPlanModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BusinessPlanPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    BusinessPlanService service;
    BusinessPlanMapper mapper;
    long part;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/businessplan/BusinessPlanMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(BusinessPlanMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,BusinessPlanService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(BusinessPlanMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();service=context.getBean(BusinessPlanService.class);
        part=mapper.parts().stream().filter(Part::active).findFirst().orElseThrow().id();
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    String value(String query){return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(query,String.class);}
    void sql(String query)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.execute(query);}}
    Plan plan(int month,long amount){return new Plan(2026,month,part,amount,-10,java.math.BigDecimal.valueOf(80));}
    String snapshot(){return value("SELECT json_build_object('plans',(SELECT json_agg(p ORDER BY biz_plan_id) FROM fin.biz_plan p),'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");}
    @Test void existingSeedPartCanCreateAndRepeatSaveKeepsNaturalKeyAndRecordsBeforeAfter()throws Exception {
        service.save(2026,List.of(plan(10,100)));service.save(2026,List.of(plan(10,200)));service.save(2026,List.of(plan(10,200)));
        assertThat(service.list(2026).items()).contains(plan(10,200));
        assertThat(value("SELECT count(*) FROM fin.biz_plan WHERE plan_ym='2026-10' AND bsn_part_id="+part)).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='fin.biz_plan' AND data_chg_ty_cd='UPDATE'")).isEqualTo("1");
        assertThat(value("SELECT bfr_val::jsonb->>'target_revenue' FROM sys.data_chg_hist WHERE tbl_nm='fin.biz_plan' AND data_chg_ty_cd='UPDATE'")).isEqualTo("100");
        assertThat(value("SELECT aft_val::jsonb->>'target_revenue' FROM sys.data_chg_hist WHERE tbl_nm='fin.biz_plan' AND data_chg_ty_cd='UPDATE'")).isEqualTo("200");
    }
    @Test void auditFailureRollsBackAllRequestedRows()throws Exception {
        service.save(2026,List.of(plan(10,100)));String before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_business_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.save(2026,List.of(plan(10,200),plan(11,300)))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void changedClosedTargetRejectsOpenRowsAndPreservesHistory()throws Exception {
        service.save(2026,List.of(plan(10,100)));
        sql("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2026-10','CLOSED') ON CONFLICT DO NOTHING");
        String before=snapshot();
        assertThatThrownBy(()->service.save(2026,List.of(plan(11,300),plan(10,200))))
            .isInstanceOfSatisfying(com.sicost.common.api.ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
        service.save(2026,List.of(plan(10,100),plan(11,300)));
        assertThat(service.list(2026).items()).contains(plan(10,100),plan(11,300));
    }
    @Test void nullableCompanyKeyAlsoUpsertsWithoutDuplicate() {
        var company=new Plan(2026,10,null,0,0,java.math.BigDecimal.ZERO);
        service.save(2026,List.of(company));service.save(2026,List.of(company));
        assertThat(value("SELECT count(*) FROM fin.biz_plan WHERE plan_ym='2026-10' AND bsn_part_id IS NULL")).isEqualTo("1");
    }
}
