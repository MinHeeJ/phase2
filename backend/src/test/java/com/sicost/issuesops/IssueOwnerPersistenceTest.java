package com.sicost.issuesops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.issuesops.IssueOwnerModels.*;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import com.sicost.common.security.PermissionService;

class IssueOwnerPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    IssueOwnerService service;
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
        factory.setMapperLocations(new ClassPathResource("mappers/issuesops/IssueOwnerMapper.xml"));
        factory.afterPropertiesSet();
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(IssueOwnerMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,IssueOwnerService.class,PermissionService.class);
        context.registerBean(IssueOwnerMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneOffset.UTC));
        context.refresh(); service=context.getBean(IssueOwnerService.class);
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES (901,'OWNER-901','가담당','FT'),(902,'OWNER-902','가담당','FT'),(903,'OWNER-903','나담당','FT'),(904,'OWNER-904','다제외','FT')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,pm_dev_id,prj_stts_cd) VALUES (901,'OWNER-P901','담당자 조회','IN',901,'RUN'),(902,'OWNER-P902','다른 프로젝트','IN',NULL,'RUN')");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null)context.close(); if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);} }
    void assignment(int id,int project,int developer,String start,String end,String status) throws Exception {
        sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES ('OWNER-A"+id+"',"+project+","+developer+",'DEV','"+start+"','"+end+"','"+status+"')");
    }
    @Test void pmAndInclusiveActiveWorkforceDeduplicateExactNamesAndSort() throws Exception {
        assignment(1,901,902,"2026-10-23","2026-10-23","ACTIVE");
        assignment(2,901,903,"2026-10-23","2026-10-23","ACTIVE");
        assignment(3,901,903,"2026-10-01","2026-11-01","ACTIVE");
        assertThat(service.options(901)).containsExactly(new IssueOwnerOption("가담당"),new IssueOwnerOption("나담당"));
    }
    @Test void otherProjectDoesNotLeakWorkforce() throws Exception {
        assignment(1,902,904,"2026-10-01","2026-11-01","ACTIVE");
        assertThat(service.options(901)).containsExactly(new IssueOwnerOption("가담당"));
    }
    @Test void pastAndFutureAssignmentsAreExcludedUsingSeoulDay() throws Exception {
        assignment(1,901,904,"2026-10-01","2026-10-22","ACTIVE");
        assignment(2,901,903,"2026-10-24","2026-11-01","ACTIVE");
        assertThat(service.options(901)).containsExactly(new IssueOwnerOption("가담당"));
    }
    @Test void endedReplacedAndCancelledAssignmentsAreExcluded() throws Exception {
        assignment(1,901,904,"2026-10-01","2026-11-01","ENDED");
        assignment(2,901,904,"2026-10-01","2026-11-01","REPLACED");
        assignment(3,901,904,"2026-10-01","2026-11-01","CANCELLED");
        assertThat(service.options(901)).containsExactly(new IssueOwnerOption("가담당"));
    }
    @Test void noPmAndNoWorkforceReturnEmpty() { assertThat(service.options(902)).isEmpty(); }
    @Test void closedProjectRemainsReadableButHasNoCurrentWorkforce() throws Exception {
        assignment(1,901,904,"2026-10-01","2026-11-01","ACTIVE");
        sql("UPDATE prj.prj SET prj_stts_cd='END' WHERE prj_id=901");
        assertThat(service.options(901)).containsExactly(new IssueOwnerOption("가담당"));
    }
}
