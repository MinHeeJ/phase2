package com.sicost.partners;

import static com.sicost.partners.PartnerModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class PartnerPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PartnerService service;
    PartnerMapper mapper;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/partners/PartnerMapper.xml"));factory.afterPropertiesSet();
        var template=new SqlSessionTemplate(factory.getObject());mapper=template.getMapper(PartnerMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,PartnerService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(PartnerMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(PartnerService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    String value(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getString(1);}}
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    PartnerSave input(String kind,String name,String biz) {return new PartnerSave(kind,name,biz,null,null,null,null,null,kind.equals("VEND")?null:"tax@company.co.kr",kind.equals("CUST")?null:"KB",kind.equals("CUST")?null:"123-456-7890",kind.equals("CUST")?null:"예금주");}
    PartnerSave edit(PartnerDetail r,String field,String value)throws Exception {var json=new ObjectMapper();var n=json.valueToTree(r.input());((com.fasterxml.jackson.databind.node.ObjectNode)n).put(field,value);return PartnerInput.parse(n,true);}
    PageResult<PartnerListItem> list(String kind,String use,String q){return service.list(new PageRequest(1,50,null),kind,use,q);}
    @Test void createCommitsFinalCodeAndSingleInsertHistory()throws Exception {
        var row=service.create(input("CUST","신규","123-45-67890"));assertThat(row.ptnr_no()).isEqualTo("C-001");assertThat(row.use_yn()).isEqualTo("Y");
        assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE ptnr_id=1 AND ptnr_se_cd IN ('CUST','BOTH') AND use_yn='Y'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.ptnr' AND obj_rec_id='1' AND data_chg_ty_cd='INSERT' AND clmn_nm IS NULL")).isEqualTo(1);
        assertThat(row.bank_cd()).isNull();assertThat(service.get(1)).isEqualTo(row);
    }
    @Test void filtersSortingAndTotalsArePersistentAndReadsHaveNoSideEffects()throws Exception {
        service.create(input("CUST","다","333-33-33333"));service.create(input("VEND","가","123-45-67890"));service.create(input("BOTH","나","222-22-22222"));service.create(input("VEND","중지","444-44-44444"));sql("UPDATE mst.ptnr SET use_yn='N' WHERE ptnr_nm='중지'");
        long rows=scalar("SELECT count(*) FROM mst.ptnr"),history=scalar("SELECT count(*) FROM sys.data_chg_hist"),access=scalar("SELECT count(*) FROM sys.acs_hist");
        var page=list(null,null,null);assertThat(page.items()).extracting(PartnerListItem::ptnr_nm).containsExactly("가","나","다");assertThat(page.size()).isEqualTo(50);assertThat(page.total()).isEqualTo(3);
        assertThat(list("VEND","Y","1234567890").items()).extracting(PartnerListItem::ptnr_nm).containsExactly("가");assertThat(list("CUST","Y","가").total()).isZero();assertThat(list(null,"",null).total()).isEqualTo(4);
        assertThat(list(null,"Y","없음").items()).isEmpty();assertThat(list(null,"Y","없음").total()).isZero();service.get(1);service.get(1);
        assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(rows);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access);
    }
    @Test void aggregatesDeduplicateTwoProjectsSixRowsFivePeopleAndExcludeInvalidAssignments()throws Exception {
        var customer=service.create(input("BOTH","집계","123-45-67890"));var vendor=service.create(input("VEND","협력","222-22-22222"));
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES (1,'P1','계획',1,'TK','PLAN'),(2,'P2','진행',1,'MO','RUN'),(3,'P3','종료',1,'MO','END')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,vndr_ptnr_id,emp_se_cd) SELECT n,'K'||n,'인력'||n,2,'OS' FROM generate_series(1,8) n");
        sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) SELECT 'A'||n,CASE WHEN n=6 THEN 2 ELSE 1 END,CASE WHEN n=6 THEN 1 ELSE n END,'BE','2026-10-01','2026-10-22' FROM generate_series(1,6) n");
        sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES ('A7',1,6,'BE','2026-10-01','2026-10-31','CANCELLED'),('A8',2,7,'BE','2026-10-01','2026-10-21','ACTIVE'),('A9',3,8,'BE','2026-10-01','2026-10-31','ACTIVE'),('A10',1,8,'BE','2026-10-23','2026-10-31','ACTIVE')");
        var row=list(null,"Y","집계").items().get(0);assertThat(row.projectCount()).isEqualTo(2);assertThat(row.assignmentPeopleCount()).isEqualTo(5);
        var v=list(null,"Y","협력").items().get(0);assertThat(v.projectCount()).isNull();assertThat(v.assignmentPeopleCount()).isEqualTo(5);assertThat(v.paymentAccount()).endsWith(" …7890");
        assertThat(new ObjectMapper().writeValueAsString(row)).doesNotContain("acnt_no","dpstr_nm","123-456-7890");assertThat(customer.ptnr_id()).isNotEqualTo(vendor.ptnr_id());
    }
    @Test void updatesOnlyChangedFieldsMasksHistoryAndNoOpKeepsTimestamp()throws Exception {
        var row=service.create(input("BOTH","원래","123-45-67890"));service.update(1,edit(row,"ptnr_nm","처음"));var first=service.get(1);service.update(1,edit(first,"ptnr_nm","나중"));assertThat(service.get(1).ptnr_nm()).isEqualTo("나중");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE' AND clmn_nm='ptnr_nm'")).isEqualTo(2);
        var before=service.get(1);String time=value("SELECT updated_at::text FROM mst.ptnr WHERE ptnr_id=1");long history=scalar("SELECT count(*) FROM sys.data_chg_hist");
        service.update(1,edit(before,"biz_reg_no","ignored"));assertThat(service.get(1)).isEqualTo(before);assertThat(value("SELECT updated_at::text FROM mst.ptnr WHERE ptnr_id=1")).isEqualTo(time);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);
        service.update(1,edit(before,"acnt_no","987-654-3210"));assertThat(value("SELECT bfr_val||':'||aft_val FROM sys.data_chg_hist WHERE clmn_nm='acnt_no'")).isEqualTo("7890:3210");assertThat(service.get(1).acnt_no()).isEqualTo("987-654-3210");
    }
    @Test void roleChangesClearNonApplicableValuesWithoutChangingIdentity()throws Exception {
        var row=service.create(input("BOTH","같은 이름","123-45-67890"));service.update(1,edit(row,"ptnr_se_cd","VEND"));assertThat(value("SELECT txinv_email_addr FROM mst.ptnr WHERE ptnr_id=1")).isNull();
        var next=service.create(input("CUST","같은 이름","222-22-22222"));assertThat(next.ptnr_id()).isNotEqualTo(row.ptnr_id());assertThat(service.get(1).biz_reg_no()).isEqualTo(row.biz_reg_no());
    }
    @Test void inactiveDuplicateRejectsWithoutNewRows()throws Exception {
        service.create(input("CUST","중지","123-45-67890"));sql("UPDATE mst.ptnr SET use_yn='N' WHERE ptnr_id=1");
        assertThatThrownBy(()->service.create(input("VEND","신규","123-45-67890"))).isInstanceOf(ApiException.class).hasMessageContaining("중지");assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(1);
    }
    @Test void auditFailureRollsBackCreation()throws Exception {
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.create(input("CUST","신규","123-45-67890"))).isInstanceOf(RuntimeException.class);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isZero();assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isZero();
    }
    @Test void auditFailureRollsBackUpdate()throws Exception {
        var before=service.create(input("BOTH","원래","123-45-67890"));sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        var input=edit(before,"chrg_nm","새 담당");assertThatThrownBy(()->service.update(1,input)).isInstanceOf(RuntimeException.class);assertThat(service.get(1)).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(1);
    }
}
