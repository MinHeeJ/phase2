package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.persistence.SchemaValidator;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SecuritySchemaTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    Flyway flyway;
    SecurityPermissionMapper mapper;
    Map<String,Integer> historicalChecksums;
    String historicalClosing;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()) { com.sicost.support.TestDatabaseDdl.create(s,database); }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        var baseline=Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").target("9").load();
        baseline.migrate();historicalChecksums=new TreeMap<>();
        for(var migration:baseline.info().applied()) historicalChecksums.put(migration.getVersion().toString(),migration.getChecksum());
        historicalClosing=text("SELECT json_agg(row_to_json(c) ORDER BY cls_ym)::text FROM fin.mth_cls c");
        flyway=Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load();flyway.migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/security/SecurityPermissionMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(SecurityPermissionMapper.class);
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if(source==null)return;
        try(var admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()) { com.sicost.support.TestDatabaseDdl.drop(s,database); }
    }
    long scalar(String sql) throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    String text(String sql) throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getString(1);}}
    void execute(String sql) throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    long actor() throws Exception {
        try(var c=source.getConnection();var s=c.prepareStatement("INSERT INTO sys.user_info(lgn_id,pswd_hash,user_nm,email_addr) VALUES(?,?,?,?) RETURNING user_id")) {
            s.setString(1,"synthetic-"+UUID.randomUUID());s.setString(2,AuthenticationProofs.hash(AuthenticationProofs.randomId()));
            s.setString(3,"합성 시험 사용자");s.setString(4,"synthetic@example.invalid");try(var r=s.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    @Test void incrementalUpgradeKeepsChecksumsClosingAndHistoricalNullActors() throws Exception {
        for(var migration:flyway.info().applied()) if(historicalChecksums.containsKey(migration.getVersion().toString()))
            assertThat(migration.getChecksum()).isEqualTo(historicalChecksums.get(migration.getVersion().toString()));
        assertThat(text("SELECT json_agg(row_to_json(c) ORDER BY cls_ym)::text FROM fin.mth_cls c")).isEqualTo(historicalClosing);
        assertThat(scalar("SELECT count(*) FROM fin.mth_cls WHERE cls_user_id IS NOT NULL")).isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero();flyway.validate();
        new SchemaValidator(source,new ObjectMapper()).validate();
        assertThat(scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema='sys' AND table_name IN ('login_session','ntf_event_key','mail_outbox')")).isEqualTo(3);
    }
    @Test void repeatedSeedPreservesCustomGrantsAndOnlyFiveBuiltIns() throws Exception {
        long role=scalar("INSERT INTO sys.role(role_nm,data_scope_cd) VALUES('"+UUID.randomUUID()+"','SELF') RETURNING role_id");
        execute("INSERT INTO sys.role_menu_auth(role_id,menu_id,inq_auth_yn) SELECT "+role+",menu_id,'Y' FROM sys.menu WHERE scrn_id='UI-16'");
        try(var c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V11__system_security_permissions.sql"));}
        assertThat(scalar("SELECT count(*) FROM sys.role WHERE bltin_yn='Y'")).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM sys.role_menu_auth a JOIN sys.menu m USING(menu_id) WHERE a.role_id="+role+" AND m.scrn_id='UI-16' AND a.inq_auth_yn='Y'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM (SELECT role_id,menu_id FROM sys.role_menu_auth GROUP BY role_id,menu_id HAVING count(*)>1) duplicates")).isZero();
    }
    @Test void newMenuHasSevenExplicitDenialsIncludingAdministrator() throws Exception {
        long menu=scalar("INSERT INTO sys.menu(menu_nm,scrn_id) VALUES('합성 메뉴','UI-99') RETURNING menu_id");
        assertThat(scalar("SELECT count(*) FROM sys.role_menu_auth WHERE menu_id="+menu+" AND inq_auth_yn='N' AND reg_auth_yn='N' AND mdfcn_auth_yn='N' AND del_auth_yn='N' AND upld_auth_yn='N' AND dwnld_auth_yn='N' AND cnfrm_auth_yn='N'")).isEqualTo(scalar("SELECT count(*) FROM sys.role"));
        long id=actor();execute("INSERT INTO sys.user_role(user_id,role_id) VALUES("+id+",1)");
        assertThat(mapper.grants(new SecurityPermissionMapper.Query(id,"UI-99","inq_auth_yn",LocalDate.of(2026,11,6)))).isEmpty();
    }
    @Test void mapperMaterializesLiveGrantsAndNeverImplicitlyAllowsExecutiveWrites() throws Exception {
        long id=actor();execute("INSERT INTO sys.user_role(user_id,role_id) VALUES("+id+",2)");
        var read=new SecurityPermissionMapper.Query(id,"UI-29","inq_auth_yn",LocalDate.of(2026,11,6));
        assertThat(mapper.grants(read)).singleElement().satisfies(g->{assertThat(g.roleId()).isEqualTo(2);assertThat(g.scope()).isEqualTo("ALL");assertThat(g.developerId()).isNull();});
        assertThat(mapper.grants(new SecurityPermissionMapper.Query(id,"UI-29","cnfrm_auth_yn",read.today()))).isEmpty();
        execute("UPDATE sys.user_info SET user_stts_cd='DISABLED' WHERE user_id="+id);
        assertThat(mapper.grants(read)).isEmpty();
    }
    @Test void hashOnlySessionAndTokenUsePhysicalTokenKey() throws Exception {
        long id=actor();String proof=AuthenticationProofs.randomId(),hash=AuthenticationProofs.hash(proof);
        try(var c=source.getConnection();var s=c.prepareStatement("INSERT INTO sys.login_session(session_id_hash,user_id,created_at,last_activity_at,expires_at) VALUES(?,?,timestamp '2026-11-06 09:00',timestamp '2026-11-06 09:00',timestamp '2026-11-06 09:30')")) {s.setString(1,hash);s.setLong(2,id);s.executeUpdate();}
        assertThat(AuthenticationProofs.matches(proof,text("SELECT session_id_hash FROM sys.login_session WHERE user_id="+id))).isTrue();
        assertThat(text("SELECT row_to_json(s)::text FROM sys.login_session s WHERE user_id="+id)).doesNotContain(proof);
        long token=scalar("INSERT INTO sys.auth_tkn(user_id,tkn_ty_cd,tkn_hash,expir_at) VALUES("+id+",'MFA','"+hash+"',timestamp '2026-11-06 09:05') RETURNING tkn_id");
        execute("INSERT INTO sys.mail_outbox(auth_tkn_id,recipient_user_id,mail_ty_cd) VALUES("+token+","+id+",'MFA')");
        assertThat(scalar("SELECT count(*) FROM sys.mail_outbox o JOIN sys.auth_tkn t ON t.tkn_id=o.auth_tkn_id")).isEqualTo(1);
    }
    @Test void unresolvedEventUniquenessIsIndependentOfRecipient() throws Exception {
        String statement="INSERT INTO sys.ntf_event_key(event_type_cd,business_object_id,threshold_or_date) VALUES('DUNNING','synthetic-invoice','2026-11')";
        execute(statement);assertThatThrownBy(()->execute(statement)).isInstanceOf(SQLException.class);
        execute("UPDATE sys.ntf_event_key SET resolved_at=CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul' WHERE business_object_id='synthetic-invoice'");
        execute("INSERT INTO sys.ntf_event_key(event_type_cd,business_object_id,threshold_or_date,occurrence_seq) VALUES('DUNNING','synthetic-invoice','2026-11',2)");
        assertThat(scalar("SELECT count(*) FROM sys.ntf_event_key WHERE business_object_id='synthetic-invoice'")).isEqualTo(2);
    }
    @Test void lastAdministratorMutationRollsBackIncludingFutureResignation() throws Exception {
        long id=actor();execute("INSERT INTO sys.user_role(user_id,role_id) VALUES("+id+",1)");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var guard=new LastAdministratorGuard(mapper);
        assertThatThrownBy(()->tx.executeWithoutResult(status->guard.protect(()->{
            try(var c=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source).prepareStatement("UPDATE sys.user_info SET rsgn_dt=date '2099-01-01' WHERE user_id=?")){c.setLong(1,id);c.executeUpdate();}catch(SQLException e){throw new IllegalStateException(e);}
        }))).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThat(scalar("SELECT count(*) FROM sys.user_info WHERE user_id="+id+" AND rsgn_dt IS NULL")).isEqualTo(1);
        assertThat(mapper.recoverableAdministrators()).isEqualTo(1);
    }
}
