package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.persistence.SchemaValidator;
import com.sicost.common.security.*;
import com.sicost.common.security.SecurityModels.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SecuritySchemaTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    Map<String,Integer> checksums;
    SecurityStateMapper mapper;
    Long actor;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) { com.sicost.support.TestDatabaseDdl.create(statement,database); }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);jdbc=new JdbcTemplate(source);
        var baseline=Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").target("9").load();baseline.migrate();
        checksums=new LinkedHashMap<>();for(var migration:baseline.info().applied()) checksums.put(migration.getVersion().toString(),migration.getChecksum());
        jdbc.update("INSERT INTO sys.data_chg_hist(tbl_nm,obj_rec_id,data_chg_ty_cd,chg_user_id) VALUES('mst.dev','historical','UPDATE',NULL)");
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/security/SecurityStateMapper.xml"));factory.afterPropertiesSet();mapper=new SqlSessionTemplate(factory.getObject()).getMapper(SecurityStateMapper.class);
        actor=jdbc.queryForObject("INSERT INTO sys.user_info(lgn_id,pswd_hash,user_nm,pswd_chg_need_yn) VALUES(?,?,?,'N') RETURNING user_id",Long.class,"synthetic."+UUID.randomUUID(),AuthenticationProof.hash(UUID.randomUUID().toString()),"시험 사용자");
        jdbc.update("INSERT INTO sys.user_role(user_id,role_id) SELECT ?,role_id FROM sys.role WHERE role_nm='시스템 관리자'",actor);
    }
    @AfterEach void removeIsolatedDatabase() throws Exception { if(source==null)return;try(var admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) { com.sicost.support.TestDatabaseDdl.drop(statement,database); } }
    long count(String sql) { return jdbc.queryForObject(sql,Long.class); }
    @Test void incrementalMigrationPreservesChecksumsHistoryAndPackagedMetadata() throws Exception {
        var flyway=Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load();
        for(var migration:flyway.info().applied()) if(checksums.containsKey(migration.getVersion().toString())) assertThat(migration.getChecksum()).isEqualTo(checksums.get(migration.getVersion().toString()));
        assertThat(flyway.migrate().migrationsExecuted).isZero();new SchemaValidator(source,new ObjectMapper()).validate();
        assertThat(count("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id='historical' AND chg_user_id IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sys.role")).isEqualTo(5);
    }
    @Test void sessionMapperStoresHashAndReadsNullableStatesWithLiveRoleIds() {
        var now=LocalDateTime.of(2026,11,1,0,0);String raw=AuthenticationProof.randomId();
        mapper.insertSession(new SessionWrite(AuthenticationProof.digest(raw),actor,now,now.plusMinutes(30),false));
        var stored=mapper.session(AuthenticationProof.digest(raw));assertThat(stored.userId()).isEqualTo(actor);assertThat(stored.revokedAt()).isNull();assertThat(mapper.roles(actor)).isNotEmpty();assertThat(mapper.permissions(actor)).isNotEmpty();
        assertThat(count("SELECT count(*) FROM sys.login_session WHERE session_id_hash='"+raw+"'")).isZero();
        mapper.revokeUser(actor,now.plusMinutes(1));assertThat(mapper.session(AuthenticationProof.digest(raw)).revokedAt()).isEqualTo(now.plusMinutes(1));
    }
    @Test void newMenuDefaultsEveryRoleToSevenDenyValuesIncludingAdministrator() {
        Long id=jdbc.queryForObject("INSERT INTO sys.menu(menu_nm) VALUES(?) RETURNING menu_id",Long.class,"시험 메뉴 "+UUID.randomUUID());
        var rows=jdbc.queryForList("SELECT inq_auth_yn||reg_auth_yn||mdfcn_auth_yn||del_auth_yn||upld_auth_yn||dwnld_auth_yn||cnfrm_auth_yn FROM sys.role_menu_auth WHERE menu_id=?",String.class,id);
        assertThat(rows).hasSize(5).containsOnly("NNNNNNN");
    }
    @Test void restartDoesNotResetEditedPermissionsOrDuplicateFiveRoles() {
        jdbc.update("UPDATE sys.role_menu_auth SET dwnld_auth_yn='N' WHERE role_id=(SELECT role_id FROM sys.role WHERE role_nm='경영진')");
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        assertThat(count("SELECT count(*) FROM sys.role_menu_auth a JOIN sys.role r USING(role_id) WHERE r.role_nm='경영진' AND a.dwnld_auth_yn='Y'")).isZero();assertThat(count("SELECT count(*) FROM sys.role")).isEqualTo(5);
    }
    @Test void unresolvedEventIsUniqueButResolvedRecurrenceCanBeInserted() {
        String insert="INSERT INTO sys.ntf_event_key(event_type_cd,business_object_id,threshold_or_date,occurrence_seq) VALUES('STAFF_SHORTAGE','part:1/job:BE','2026-11',?)";
        jdbc.update(insert,1);assertThatThrownBy(()->jdbc.update(insert,2)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        jdbc.update("UPDATE sys.ntf_event_key SET resolved_at=CURRENT_TIMESTAMP WHERE event_type_cd='STAFF_SHORTAGE'");jdbc.update(insert,2);
        assertThat(count("SELECT count(*) FROM sys.ntf_event_key WHERE event_type_cd='STAFF_SHORTAGE'")).isEqualTo(2);
    }
    @Test void outboxForeignKeyUsesActualTokenIdAndDoesNotContainProofPayload() {
        Long token=jdbc.queryForObject("INSERT INTO sys.auth_tkn(user_id,tkn_ty_cd,tkn_hash,expir_at) VALUES(?,'MFA',?,CURRENT_TIMESTAMP+interval '5 minutes') RETURNING tkn_id",Long.class,actor,AuthenticationProof.hash(AuthenticationProof.otp()));
        jdbc.update("INSERT INTO sys.mail_outbox(auth_tkn_id,recipient_user_id,mail_ty_cd) VALUES(?,?,'MFA')",token,actor);
        assertThat(count("SELECT count(*) FROM sys.mail_outbox m JOIN sys.auth_tkn t ON t.tkn_id=m.auth_tkn_id")).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("INSERT INTO sys.mail_outbox(recipient_user_id,mail_ty_cd) VALUES(?,'MFA')",actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema='sys' AND table_name='mail_outbox' AND column_name IN ('otp','token','body','recipient_email')")).isZero();
    }
}
