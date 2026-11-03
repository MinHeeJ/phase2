package com.sicost.dashboardops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.dashboard.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DashboardAuditPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    DashboardAuditMapper mapper;
    JdbcTemplate jdbc;
    DataSourceTransactionManager transactions;
    @BeforeEach void isolatedDatabase() throws Exception {
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
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mappers/**/*.xml"));
        factory.afterPropertiesSet();mapper=new SqlSessionTemplate(factory.getObject()).getMapper(DashboardAuditMapper.class);
        jdbc=new JdbcTemplate(source);transactions=new DataSourceTransactionManager(source);
    }
    @AfterEach void cleanup() throws Exception {
        if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    DashboardAuditService proxy(DashboardAuditMapper target) {
        var interceptor=new TransactionInterceptor();interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy=new ProxyFactory(new DashboardAuditService(target,new CurrentUser()));proxy.setProxyTargetClass(true);
        proxy.addAdvice(interceptor);return (DashboardAuditService)proxy.getProxy();
    }
    @Test void oneRealDownloadWritesOneExistingAuditAndNoBusinessOrNotificationRows() {
        var beans=new DefaultListableBeanFactory();var json=new ObjectMapper();
        beans.registerSingleton("dashboardReadSource",(DashboardReadSource)c->DashboardExportFixtures.response(json,c));
        var service=new DashboardExportService(beans.getBeanProvider(DashboardReadSource.class),
            new DashboardWorkbook(new ExcelExportService()),proxy(mapper),new CurrentUser(),new PermissionService(),
            Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneOffset.UTC));
        String before=businessState();
        assertThat(service.download("2026-09").bytes()).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys.acs_hist WHERE scrn_id='UI-16' AND acs_ty_cd='DOWNLOAD'",Long.class)).isEqualTo(1L);
        var row=jdbc.queryForMap("SELECT user_id,obj_desc,dtl_desc FROM sys.acs_hist WHERE scrn_id='UI-16'");
        assertThat(row.get("user_id")).isNull();assertThat(row.get("obj_desc")).isEqualTo("dashboard_2026-09.xlsx");
        assertThat(row.get("dtl_desc").toString()).contains("basisMonth","asOf","columns");
        assertThat(businessState()).isEqualTo(before);
    }
    String businessState() {
        return jdbc.queryForObject("SELECT json_build_array((SELECT count(*) FROM sys.ntf), (SELECT count(*) FROM fin.mth_cls), (SELECT count(*) FROM fin.mth_pl), (SELECT count(*) FROM cst.cst_aloc), (SELECT count(*) FROM sys.data_chg_hist))::text",String.class);
    }
    @Test void auditUsesWritableNewTransactionEvenInsideReadOnlyCaller() {
        var readOnly=new TransactionTemplate(transactions);readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(s->proxy(mapper).record("dashboard_2026-09.xlsx","{}"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys.acs_hist WHERE scrn_id='UI-16'",Long.class)).isEqualTo(1L);
    }
    @Test void auditInsertRollsBackOnFailureAndDoesNotLeaveSuccessRecord() {
        DashboardAuditMapper failing=d->{mapper.download(d);throw new IllegalStateException("audit failure");};
        assertThatThrownBy(()->proxy(failing).record("dashboard_2026-09.xlsx","{}"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys.acs_hist WHERE scrn_id='UI-16'",Long.class)).isZero();
    }
}
