package com.sicost.partners;

import static com.sicost.partners.PartnerModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sicost.common.security.*;
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

class PartnerUpdatePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PartnerService service;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/partners/PartnerMapper.xml"));factory.afterPropertiesSet();
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(PartnerMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,PartnerService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(PartnerMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(PartnerService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String value(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getString(1);}}
    PartnerDetail create(){return service.create(new PartnerSave("BOTH","가 거래처","123-45-67890",null,null,"담당",null,null,"tax@company.co.kr","KB","123-456-7890","예금주"));}
    PartnerSave edit(PartnerDetail row,String field,String value){
        ObjectNode body=new ObjectMapper().valueToTree(row.input());body.put(field,value);return PartnerInput.parse(body,true);
    }
    @Test void nameOnlyChangeKeepsAllOtherColumnsAndHasOneHistoryRow()throws Exception {
        var before=create();sql("UPDATE mst.ptnr SET chrg_email_addr='contact@company.co.kr' WHERE ptnr_id=1");
        service.update(1,edit(before,"ptnr_nm","새 상호"));
        assertThat(service.get(1).input()).isEqualTo(new PartnerSave("BOTH","새 상호",before.biz_reg_no(),null,null,"담당",null,null,"tax@company.co.kr","KB","123-456-7890","예금주"));
        assertThat(value("SELECT chrg_email_addr FROM mst.ptnr WHERE ptnr_id=1")).isEqualTo("contact@company.co.kr");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).isEqualTo("1");
        assertThat(value("SELECT clmn_nm||':'||bfr_val||':'||aft_val FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).isEqualTo("ptnr_nm:가 거래처:새 상호");
    }
    @Test void firstNoOpDoesNotFireTimestampTriggerOrAppendHistory()throws Exception {
        var before=create();assertThat(value("SELECT updated_at::text FROM mst.ptnr WHERE ptnr_id=1")).isNull();
        service.update(1,edit(before,"biz_reg_no","ignored"));
        assertThat(service.get(1)).isEqualTo(before);
        assertThat(value("SELECT updated_at::text FROM mst.ptnr WHERE ptnr_id=1")).isNull();
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo("1");
    }
    @Test void paymentAuditNeverStoresTheFullAccount()throws Exception {
        var before=create();service.update(1,edit(before,"acnt_no","987-654-3210"));
        assertThat(service.get(1).acnt_no()).isEqualTo("987-654-3210");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE'")).isEqualTo("1");
        assertThat(value("SELECT bfr_val||':'||aft_val FROM sys.data_chg_hist WHERE clmn_nm='acnt_no'")).isEqualTo("7890:3210");
    }
    @Test void categoryChangePreservesExistingProjectAndDeveloperLinks()throws Exception {
        var before=create();
        sql("INSERT INTO prj.prj(prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES ('P1','진행',1,'TK','RUN')");
        sql("INSERT INTO mst.dev(mng_no,dev_nm,vndr_ptnr_id,emp_se_cd) VALUES ('K1','소속 인력',1,'OS')");
        service.update(1,edit(before,"ptnr_se_cd","CUST"));
        assertThat(value("SELECT bank_cd FROM mst.ptnr WHERE ptnr_id=1")).isNull();
        assertThat(value("SELECT acnt_no FROM mst.ptnr WHERE ptnr_id=1")).isNull();
        assertThat(value("SELECT dpstr_nm FROM mst.ptnr WHERE ptnr_id=1")).isNull();
        assertThat(value("SELECT cust_ptnr_id FROM prj.prj WHERE prj_no='P1'")).isEqualTo("1");
        assertThat(value("SELECT vndr_ptnr_id FROM mst.dev WHERE mng_no='K1'")).isEqualTo("1");
    }
    @Test void auditFailureRollsBackEveryChangedColumn()throws Exception {
        var before=create();sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        ObjectNode body=new ObjectMapper().valueToTree(before.input());body.put("ptnr_nm","새 상호");body.put("acnt_no","987-654-3210");
        assertThatThrownBy(()->service.update(1,PartnerInput.parse(body,true))).isInstanceOf(RuntimeException.class);
        assertThat(service.get(1)).isEqualTo(before);
        assertThat(value("SELECT updated_at::text FROM mst.ptnr WHERE ptnr_id=1")).isNull();
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo("1");
    }
}
