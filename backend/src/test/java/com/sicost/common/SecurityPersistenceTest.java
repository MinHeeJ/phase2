package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.sicost.common.security.*;
import static com.sicost.common.security.AccessPolicyModels.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SecurityPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    Flyway flyway;
    AccessPolicyMapper mapper;
    Long userId;
    String hash;
    LocalDateTime now=LocalDateTime.of(2026,10,23,0,0);
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.create(s,database); }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        flyway=Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load();flyway.migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/security/AccessPolicyMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AccessPolicyMapper.class);
        String login="synthetic_"+UUID.randomUUID().toString().replace("-","");
        String encoded=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(AuthenticationProofs.randomSessionId());
        try(var c=source.getConnection();var s=c.prepareStatement("INSERT INTO sys.user_info(lgn_id,pswd_hash,user_nm,pswd_chg_need_yn) VALUES(?,?,'합성 사용자','N') RETURNING user_id")) {
            s.setString(1,login);s.setString(2,encoded);try(var r=s.executeQuery()){r.next();userId=r.getLong(1);}
        }
        hash=AuthenticationProofs.hash(AuthenticationProofs.randomSessionId());
        execute("INSERT INTO sys.user_role(user_id,role_id) VALUES("+userId+",1)");
        session(hash);
    }
    void session(String digest)throws Exception {
        try(var c=source.getConnection();var s=c.prepareStatement("INSERT INTO sys.login_session(session_id_hash,user_id,created_at,last_activity_at,expires_at) VALUES(?,?,?,?,?)")) {
            s.setString(1,digest);s.setLong(2,userId);s.setObject(3,now);s.setObject(4,now);s.setObject(5,now.plusMinutes(30));s.executeUpdate();
        }
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.drop(s,database); }
    }
    void execute(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    @Test void migrationsPreserveChecksumsAndExistingEditedPermissions()throws Exception {
        var checksums=new LinkedHashMap<String,Integer>();for(var m:flyway.info().applied())checksums.put(m.getVersion().toString(),m.getChecksum());
        execute("UPDATE sys.role_menu_auth SET dwnld_auth_yn='N' WHERE role_id=1");
        assertThat(flyway.migrate().migrationsExecuted).isZero();flyway.validate();
        for(var m:flyway.info().applied())assertThat(m.getChecksum()).isEqualTo(checksums.get(m.getVersion().toString()));
        assertThat(scalar("SELECT count(*) FROM sys.role WHERE bltin_yn='Y'")).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM sys.role_menu_auth WHERE role_id=1 AND dwnld_auth_yn='Y'")).isZero();
    }
    @Test void realMapperMaterializesIdentityAndRoleAtSeoulBoundary() {
        var account=mapper.activeSession(new SessionLookup(userId,hash,now));
        assertThat(account.userId()).isEqualTo(userId);assertThat(account.name()).isEqualTo("합성 사용자");
        assertThat(mapper.roles(userId).get(0).admin()).isTrue();
        assertThat(mapper.grants(new PermissionLookup(userId,"UI-27","read"))).isNotEmpty();
        assertThat(mapper.parts(userId)).isEmpty();assertThat(mapper.projects(userId)).isEmpty();
    }
    @Test void exactThirtyMinuteBoundaryAndResignationDenyWithoutCleanup()throws Exception {
        assertThat(mapper.activeSession(new SessionLookup(userId,hash,now.plusMinutes(29)))).isNotNull();
        assertThat(mapper.activeSession(new SessionLookup(userId,hash,now.plusMinutes(30)))).isNull();
        execute("UPDATE sys.user_info SET rsgn_dt=DATE '2026-10-23' WHERE user_id="+userId);
        assertThat(mapper.activeSession(new SessionLookup(userId,hash,now))).isNull();
    }
    @Test void relationRemovalRevokesAllNodeSessionsAtomically()throws Exception {
        session(AuthenticationProofs.hash(AuthenticationProofs.randomSessionId()));
        execute("DELETE FROM sys.user_role WHERE user_id="+userId);
        assertThat(scalar("SELECT count(*) FROM sys.login_session WHERE user_id="+userId+" AND revoked_at IS NULL")).isZero();
    }
    @Test void newMenuIsDeniedForEveryRoleIncludingAdministrator()throws Exception {
        execute("INSERT INTO sys.menu(menu_nm,scrn_id) VALUES('합성 메뉴','UI-99')");
        assertThat(scalar("SELECT count(*) FROM sys.role_menu_auth a JOIN sys.menu m USING(menu_id) WHERE m.scrn_id='UI-99'")).isEqualTo(scalar("SELECT count(*) FROM sys.role"));
        assertThat(scalar("SELECT count(*) FROM sys.role_menu_auth a JOIN sys.menu m USING(menu_id) WHERE m.scrn_id='UI-99' AND (inq_auth_yn<>'N' OR reg_auth_yn<>'N' OR mdfcn_auth_yn<>'N' OR del_auth_yn<>'N' OR upld_auth_yn<>'N' OR dwnld_auth_yn<>'N' OR cnfrm_auth_yn<>'N')")).isZero();
    }
    @Test void pendingProofSupportsNoDummyHashAndIssuedProofRequiresHash()throws Exception {
        execute("INSERT INTO sys.auth_tkn(user_id,tkn_ty_cd,issuance_stts_cd) VALUES("+userId+",'MFA','PENDING')");
        assertThat(scalar("SELECT count(*) FROM sys.auth_tkn WHERE tkn_hash IS NULL AND expir_at IS NULL AND issuance_stts_cd='PENDING'")).isEqualTo(1);
        assertThatThrownBy(()->execute("INSERT INTO sys.auth_tkn(user_id,tkn_ty_cd,issuance_stts_cd) VALUES("+userId+",'MFA','ISSUED')")).isInstanceOf(SQLException.class);
    }
    @Test void eventOccurrenceAndRecipientAreUniqueAndHistoricalNotificationsRemainReadable()throws Exception {
        execute("INSERT INTO sys.ntf(user_id,ntf_ty_cd,ttl_nm) VALUES("+userId+",'UPLOAD','과거 알림')");
        execute("INSERT INTO sys.ntf_event_key(event_type_cd,business_object_id,threshold_or_date,opened_at) VALUES('STAFF_SHORTAGE','part-job','2026-11',TIMESTAMP '2026-10-23 00:00:00')");
        long event=scalar("SELECT max(event_key_id) FROM sys.ntf_event_key");
        execute("INSERT INTO sys.ntf(user_id,ntf_ty_cd,ttl_nm,event_key_id) VALUES("+userId+",'STAFF_SHORTAGE','합성 알림',"+event+")");
        assertThatThrownBy(()->execute("INSERT INTO sys.ntf(user_id,ntf_ty_cd,ttl_nm,event_key_id) VALUES("+userId+",'STAFF_SHORTAGE','중복',"+event+")")).isInstanceOf(SQLException.class);
        assertThat(scalar("SELECT count(*) FROM sys.ntf WHERE user_id="+userId)).isEqualTo(2);
    }
    @Test void historyNullActorAndResultAreNeverBackfilled()throws Exception {
        execute("INSERT INTO sys.data_chg_hist(tbl_nm,obj_rec_id,data_chg_ty_cd) VALUES('sys.user_info','historical','INSERT')");
        execute("INSERT INTO sys.acs_hist(acs_ty_cd) VALUES('LOGIN')");
        flyway.migrate();
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id='historical' AND chg_user_id IS NULL")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE user_id IS NULL AND result_cd IS NULL")).isEqualTo(1);
    }
}
