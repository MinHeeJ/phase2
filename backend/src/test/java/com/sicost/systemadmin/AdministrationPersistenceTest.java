package com.sicost.systemadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.authentication.AuthenticationService;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdministrationPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,username,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    MockMvc mvc;
    Long actor;
    @BeforeEach void setup()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");username=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank()&&username!=null&&password!=null,"Real PostgreSQL requires explicit test database connection settings");
        database="sicost_security_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,username,password);var s=c.createStatement()){s.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,username,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mappers/**/*.xml"));factory.afterPropertiesSet();
        var template=new SqlSessionTemplate(factory.getObject());
        try(var c=source.getConnection();var insert=c.prepareStatement("INSERT INTO sys.user_info(lgn_id,pswd_hash,user_nm,email_addr,mfa_yn,pswd_chg_need_yn) VALUES(?,?,?,'test@example.invalid','Y','N') RETURNING user_id")) {
            insert.setString(1,"test-"+UUID.randomUUID());insert.setString(2,AuthenticationProof.hash(AuthenticationProof.randomId()));insert.setString(3,"격리 관리자");
            try(var result=insert.executeQuery()){result.next();actor=result.getLong(1);}
            try(var roles=c.prepareStatement("INSERT INTO sys.user_role(user_id,role_id) SELECT ?,role_id FROM sys.role WHERE role_nm='시스템 관리자'")){roles.setLong(1,actor);roles.executeUpdate();}
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new SecurityPrincipal(actor,"격리 관리자",List.of(),"a".repeat(64),false),null,List.of()));
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,AdministrationService.class,AuthorizationService.class,CurrentUser.class,ExcelExportService.class);
        context.registerBean(AdministrationMapper.class,()->template.getMapper(AdministrationMapper.class));context.registerBean(SecurityStateMapper.class,()->template.getMapper(SecurityStateMapper.class));
        context.registerBean(AuthenticationService.class,()->mock(AuthenticationService.class));context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"),SessionService.SEOUL));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));context.refresh();
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new AdministrationController(context.getBean(AdministrationService.class))).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void cleanup()throws Exception {
        SecurityContextHolder.clearContext();if(context!=null)context.close();if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,username,password);var s=c.createStatement()){s.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    @Test void newGroupCreatesSevenDeniedActionsForEveryExistingRole()throws Exception {
        String name="group-"+UUID.randomUUID();String body=new ObjectMapper().writeValueAsString(Map.of("name",name,"sortOrder",1,"useYn","Y","mobileVisible",true));
        var response=mvc.perform(post("/api/admin/menus").contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
        long id=new ObjectMapper().readTree(response.getResponse().getContentAsString()).path("data").path("menuId").asLong();
        try(var c=source.getConnection();var q=c.prepareStatement("SELECT count(*) AS total,count(*) FILTER(WHERE inq_auth_yn||reg_auth_yn||mdfcn_auth_yn||del_auth_yn||upld_auth_yn||dwnld_auth_yn||cnfrm_auth_yn='NNNNNNN') AS denied FROM sys.role_menu_auth WHERE menu_id=?")) {
            q.setLong(1,id);try(var r=q.executeQuery()){r.next();assertThat(r.getLong("total")).isGreaterThanOrEqualTo(5);assertThat(r.getLong("denied")).isEqualTo(r.getLong("total"));}
        }
        mvc.perform(get("/api/admin/menus")).andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.menuId == "+id+")].name").value(name));
    }
    @Test void lastAdministratorRetirement409RollsBackRealRow()throws Exception {
        mvc.perform(patch("/api/admin/users/"+actor).contentType("application/json").content("{\"resignationDate\":\"2099-01-01\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        try(var c=source.getConnection();var q=c.prepareStatement("SELECT rsgn_dt,user_stts_cd FROM sys.user_info WHERE user_id=?")){q.setLong(1,actor);try(var r=q.executeQuery()){r.next();assertThat(r.getDate(1)).isNull();assertThat(r.getString(2)).isEqualTo("ACTIVE");}}
    }
    @Test void roleCreateAndCopyHaveIndependentIdsAndResetPrivileges()throws Exception {
        var service=context.getBean(AdministrationService.class);var json=new ObjectMapper();
        var created=service.createRole(json.readTree(json.writeValueAsString(Map.of("name","source-"+UUID.randomUUID(),"useYn","Y","dataScope","ALL","admin",true,"sensitiveInquiry",true,"partStandardChange",true,"riskConfirmed",true))));
        long id=((Number)created.get("roleId")).longValue();String copiedName="copy-"+UUID.randomUUID();
        var copied=service.copyRole(id,json.createObjectNode().put("name",copiedName));
        assertThat(copied.get("roleId")).isNotEqualTo(id);assertThat(copied.get("admin")).isEqualTo(false);assertThat(copied.get("builtin")).isEqualTo(false);assertThat(copied.get("sensitiveInquiry")).isEqualTo(false);assertThat(copied.get("partStandardChange")).isEqualTo(false);
        mvc.perform(get("/api/admin/roles/"+copied.get("roleId"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value(copiedName));
    }
}
