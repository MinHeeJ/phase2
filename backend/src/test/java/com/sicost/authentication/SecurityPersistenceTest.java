package com.sicost.authentication;

import com.sicost.useraccounts.ManagementMapper;
import com.sicost.notifications.NotificationMapper;
import com.sicost.businesscontacts.BusinessContactMapper;
import com.sicost.common.security.SecurityTime;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.authentication.AuthenticationService.*;

class SecurityPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    SqlSessionTemplate session;
    AuthenticationMapper auth;
    ManagementMapper management;
    NotificationMapper notifications;
    JdbcTemplate jdbc;
    LocalDateTime now=LocalDateTime.of(2026,11,6,9,0);
    @BeforeEach void setup() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null && !adminUrl.isBlank(),"Selected-engine test needs an isolated PostgreSQL connection");user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null && password!=null);
        database="security_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(adminUrl,user,password);var statement=connection.createStatement()) {statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mappers/**/*.xml"));factory.afterPropertiesSet();session=new SqlSessionTemplate(factory.getObject());
        auth=session.getMapper(AuthenticationMapper.class);management=session.getMapper(ManagementMapper.class);notifications=session.getMapper(NotificationMapper.class);jdbc=new JdbcTemplate(source);
    }
    @AfterEach void cleanup() throws Exception {
        if(source==null) return;
        try(var connection=DriverManager.getConnection(adminUrl,user,password);var statement=connection.createStatement()) {statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    long account() {return management.createUser(values("loginId","security."+UUID.randomUUID(),"name","격리 시험 계정","email","isolated@example.invalid","passwordHash",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("Trial!1234")));}
    @Test void realMappersMaterializeAccountsAndHashOnlySessionsAndConsumeProofOnce() {
        long id=account();var row=auth.account(values("id",id));assertThat(number(row,"id")).isEqualTo(id);assertThat(row.get("passwordChangeRequired")).isEqualTo(true);
        String proof=com.sicost.common.security.AuthenticationProofs.randomId(),hash=com.sicost.common.security.AuthenticationProofs.hash(proof);
        auth.insertSession(values("id",id,"sessionHash",hash,"csrfHash",hash,"now",now,"expiresAt",now.plusMinutes(30),"passwordChangeRequired",true));
        assertThat(auth.session(values("sessionHash",hash,"now",now))).containsEntry("name","격리 시험 계정");assertThat(auth.session(values("sessionHash",hash,"now",now.plusMinutes(30)))).isNull();
        long token=auth.insertChallenge(values("id",id,"type","RESET","proofHash",hash,"chainHash",hash,"failures",0,"now",now,"expiresAt",now.plusMinutes(15)));
        assertThat(auth.consume(values("tokenId",token,"now",now))).isEqualTo(1);assertThat(auth.consume(values("tokenId",token,"now",now))).isZero();
        auth.revoke(values("id",id,"now",now.plusMinutes(1)));assertThat(auth.session(values("sessionHash",hash,"now",now.plusMinutes(2)))).isNull();
    }
    @Test void notificationOwnershipAndCurrentGrantRemovalPreserveOriginalReadState() {
        long id=account();long other=account();
        long role=management.createRole(values("name","격리 역할 "+UUID.randomUUID(),"description","시험","useYn","Y","dataScope","ALL","sensitiveInquiry",false,"partStandardChange",false,"admin",false));
        management.insertAssignment(values("id",id,"kind","roles","value",role));
        long menu=jdbc.queryForObject("SELECT menu_id FROM sys.menu WHERE scrn_id='UI-12'",Long.class);
        management.savePermission(values("id",role,"menuId",menu,"inq",true,"reg",false,"mdfcn",false,"del",false,"upld",false,"dwnld",false,"cnfrm",false,"now",now));
        long ntf=jdbc.queryForObject("INSERT INTO sys.ntf(user_id,ntf_ty_cd,ttl_nm,link_scrn_id) VALUES(?,'UPLOAD','격리 알림','UI-12') RETURNING ntf_id",Long.class,id);
        var query=values("userId",id,"now",now,"size",20,"offset",0,"unreadOnly",true,"id",ntf);
        assertThat(notifications.count(query)).isEqualTo(1);assertThat(notifications.list(query)).hasSize(1);
        assertThat(notifications.owned(values("userId",other,"id",ntf))).isNull();
        jdbc.update("UPDATE sys.role_menu_auth SET inq_auth_yn='N' WHERE role_id=? AND menu_id=?",role,menu);
        assertThat(notifications.count(query)).isZero();assertThat(notifications.readAll(query)).isZero();assertThat(notifications.detail(query)).isNull();
        assertThat(jdbc.queryForObject("SELECT read_yn FROM sys.ntf WHERE ntf_id=?",String.class,ntf)).isEqualTo("N");
        jdbc.update("UPDATE sys.role_menu_auth SET inq_auth_yn='Y' WHERE role_id=? AND menu_id=?",role,menu);
        assertThat(notifications.count(query)).isEqualTo(1);assertThat(notifications.readAll(query)).isEqualTo(1);assertThat(notifications.count(query)).isZero();
    }
    @Test void usersAndAuditFiltersExecuteWithAbsentOptionalParameters() {
        long id=account();var query=values("size",20,"offset",0,"page",0);
        assertThat(management.users(query)).anyMatch(row -> number(row,"id")==id);assertThat(management.userCount(query)).isPositive();
        auth.audit(values("id",id,"action","LOGIN","screenId","UI-22","target",Long.toString(id),"detail",null,"ip","127.0.0.1","now",now));
        assertThat(management.history(values("userId",Long.toString(id),"size",20,"offset",0))).hasSize(1);
        assertThat(management.permissions(1L)).isNotEmpty();assertThat(management.menus()).isNotEmpty();assertThat(management.roles(Map.of())).isNotEmpty();
        var contacts=session.getMapper(BusinessContactMapper.class);
        assertThat(contacts.developer(values("userId",id,"screenId","UI-02","id",Long.MAX_VALUE,"today",now.toLocalDate(),"purpose","STAFF_OPERATION"))).isNull();
        assertThat(contacts.partner(values("userId",id,"screenId","UI-15","id",Long.MAX_VALUE,"today",now.toLocalDate(),"purpose","CONTRACT_SALES"))).isNull();
    }
}
