package com.sicost.workforce;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.*;
import com.sicost.candidates.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.developers.*;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Per-test disposable PostgreSQL database, following DeveloperPersistenceTest isolation. */
public final class WorkforceTestSupport implements AutoCloseable {
    @Configuration @EnableTransactionManagement public static class Transactions {}
    public final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public final DriverManagerDataSource source;
    public final AnnotationConfigApplicationContext context;
    public final MockMvc mvc;
    public final CandidateService candidates;
    public final WorkforceForecastService workforce;
    private final String adminUrl,user,password,database;
    public WorkforceTestSupport()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank()&&user!=null&&password!=null,"Real PostgreSQL requires TEST_DATABASE_URL and test database credentials");
        database="sicost_workforce_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){s.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        context=new AnnotationConfigApplicationContext();
        try {
            Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
            var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
            factory.setMapperLocations(new ClassPathResource("mappers/candidates/CandidateMapper.xml"),new ClassPathResource("mappers/workforce/WorkforceForecastMapper.xml"),new ClassPathResource("mappers/developers/DeveloperMapper.xml"));factory.afterPropertiesSet();
            var session=new SqlSessionTemplate(factory.getObject());
            context.register(Transactions.class,CandidateService.class,WorkforceForecastService.class,DeveloperService.class,CurrentUser.class,PermissionService.class);
            context.registerBean(CandidateMapper.class,()->session.getMapper(CandidateMapper.class));context.registerBean(WorkforceForecastMapper.class,()->session.getMapper(WorkforceForecastMapper.class));context.registerBean(DeveloperMapper.class,()->session.getMapper(DeveloperMapper.class));
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
            context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T15:30:00Z"),ZoneOffset.UTC));context.refresh();
            candidates=context.getBean(CandidateService.class);workforce=context.getBean(WorkforceForecastService.class);
            mvc=MockMvcBuilders.standaloneSetup(new CandidateController(candidates),new WorkforceController(workforce)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        } catch(Exception ex){close();throw ex;}
    }
    public void sql(String command)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(command);}}
    public long scalar(String command)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(command)){if(!r.next())throw new IllegalStateException("Missing assertion row");return r.getLong(1);}}
    public String value(String command)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(command)){if(!r.next())throw new IllegalStateException("Missing assertion row");return r.getString(1);}}
    @Override public void close()throws Exception {context.close();try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){s.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
}
