package com.sicost.partners;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.time.*;
import java.util.*;
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
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){s.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/partners/PartnerMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(PartnerMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,PartnerService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(PartnerMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(PartnerService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){s.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    Map<String,Object> input(String kind,String name,String biz){var b=PartnerApiTest.input(kind);b.put("ptnr_nm",name);b.put("biz_reg_no",biz);return b;}
    String create(String kind,String name,String biz){return service.createPartner(json.valueToTree(input(kind,name,biz))).ptnr_id();}
    @Test void defaultsFiltersTotalsAndReadsHaveNoSideEffects()throws Exception {
        create("CUST","가 상호","111-11-11111");create("VEND","나 상호","123-45-67890");create("BOTH","다 상호","333-33-33333");String stopped=create("VEND","중지","444-44-44444");sql("UPDATE mst.ptnr SET use_yn='N' WHERE ptnr_id="+stopped);
        long rows=scalar("SELECT count(*) FROM mst.ptnr"),events=scalar("SELECT count(*) FROM sys.data_chg_hist");
        var page=service.listPartners(Map.of());assertThat(page.items()).extracting(PartnerModels.PartnerListItem::ptnr_nm).containsExactly("가 상호","나 상호","다 상호");assertThat(page.size()).isEqualTo(50);assertThat(page.total()).isEqualTo(3);
        var filtered=service.listPartners(Map.of("kind","VEND","q","1234567890"));assertThat(filtered.items()).hasSize(1);assertThat(filtered.total()).isEqualTo(1);assertThat(filtered.items().get(0).ptnr_se_cd()).isEqualTo("VEND");assertThat(filtered.items().get(0).projectCount()).isNull();assertThat(filtered.items().get(0).paymentAccount()).isEqualTo("국민은행 …9012");
        assertThat(service.listPartners(Map.of("q","없는 상호")).total()).isZero();assertThat(service.getPartner(stopped).use_yn()).isEqualTo("N");service.getPartner(stopped);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(rows);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(events);
        for(String sort:List.of("paymentAccount,asc","use_yn,desc","projectCount,desc","kindName,asc","assignmentPeopleCount,desc")) service.listPartners(Map.of("sort",sort));
    }
    @Test void createAndUpdatePersistExactHistoryAndNoChangePreservesTimestamp()throws Exception {
        String id=create("BOTH","상호","123-45-67890");assertThat(service.getPartner(id).ptnr_no()).isEqualTo("C-001");assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id='"+id+"' AND tbl_nm='mst.ptnr' AND data_chg_ty_cd='INSERT' AND clmn_nm IS NULL AND chg_user_id IS NULL")).isEqualTo(1);
        var b=input("BOTH","새 상호","ignored");service.updatePartner(id,json.valueToTree(b));assertThat(service.getPartner(id).biz_reg_no()).isEqualTo("123-45-67890");assertThat(service.getPartner(id).ptnr_nm()).isEqualTo("새 상호");assertThat(service.getPartner(id).acnt_no()).isEqualTo("1234-5678-9012");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE' AND clmn_nm='ptnr_nm' AND bfr_val='상호' AND aft_val='새 상호'")).isEqualTo(1);
        String before;try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT updated_at::text FROM mst.ptnr WHERE ptnr_id="+id)){r.next();before=r.getString(1);}
        service.updatePartner(id,json.valueToTree(b));try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT updated_at::text FROM mst.ptnr WHERE ptnr_id="+id)){r.next();assertThat(r.getString(1)).isEqualTo(before);}
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(2);
        b.put("acnt_no","9876-5432-1098");service.updatePartner(id,json.valueToTree(b));assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE clmn_nm='acnt_no' AND bfr_val='9012' AND aft_val='1098'")).isEqualTo(1);
        b.put("chrg_nm","담당자");service.updatePartner(id,json.valueToTree(b));assertThat(service.getPartner(id).chrg_nm()).isEqualTo("담당자");b.put("ptnr_nm","마지막 상호");service.updatePartner(id,json.valueToTree(b));assertThat(service.getPartner(id).ptnr_nm()).isEqualTo("마지막 상호");
    }
    @Test void invalidAndDuplicateWritesRollbackAndRoleChangeClearsFields()throws Exception {
        String id=create("BOTH","상호","123-45-67890");sql("UPDATE mst.ptnr SET use_yn='N' WHERE ptnr_id="+id);
        assertThatThrownBy(()->create("CUST","다른 상호","123-45-67890")).hasMessage("이미 등록된 사업자번호입니다. (상호)");
        for(String field:List.of("biz_reg_no","txinv_email_addr","acnt_no")){var bad=input("BOTH","상호","123-45-67890");bad.put(field,"");assertThatThrownBy(()->service.createPartner(json.valueToTree(bad))).isInstanceOf(com.sicost.common.api.ApiException.class);}
        var bad=input("BOTH","상호","ignored");bad.remove("txinv_email_addr");assertThatThrownBy(()->service.updatePartner(id,json.valueToTree(bad))).isInstanceOf(com.sicost.common.api.ApiException.class);assertThat(service.getPartner(id).txinv_email_addr()).isEqualTo("tax@company.co.kr");assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(1);
        service.updatePartner(id,json.valueToTree(input("VEND","상호","ignored")));assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE txinv_email_addr IS NULL")).isEqualTo(1);
        create("VEND","상호","222-22-22222");assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(2);
    }
    @Test void auditFailureRollsBackCreationAndUpdate()throws Exception {
        String id=create("BOTH","상호","123-45-67890");sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_partner_history CHECK (false) NOT VALID");
        assertThatThrownBy(()->create("CUST","새 상호","222-22-22222")).isInstanceOf(RuntimeException.class);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(1);
        assertThatThrownBy(()->service.updatePartner(id,json.valueToTree(input("BOTH","새 상호","ignored")))).isInstanceOf(RuntimeException.class);assertThat(service.getPartner(id).ptnr_nm()).isEqualTo("상호");
    }
    @Test void twoProjectsSixAssignmentsFivePeopleExcludeInvalidPaths()throws Exception {
        String id=create("BOTH","고객","123-45-67890");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES (1,'P-1','진행1',"+id+",'TK','RUN'),(2,'P-2','진행2',"+id+",'TK','HOLD'),(3,'P-3','종료',"+id+",'TK','END')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,vndr_ptnr_id) SELECT n,'K-'||n,'인력'||n,'OS',"+id+" FROM generate_series(1,8) n");
        sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) SELECT 'A-'||n,CASE WHEN n=6 THEN 2 ELSE 1 END,CASE WHEN n=6 THEN 1 ELSE n END,'BE','2026-10-22','2026-10-22' FROM generate_series(1,6) n");
        sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES ('A-END',3,6,'BE','2026-10-01','2026-10-30','ACTIVE'),('A-CANCEL',1,7,'BE','2026-10-01','2026-10-30','CANCELLED'),('A-OLD',1,8,'BE','2026-10-01','2026-10-21','ACTIVE')");
        var row=service.listPartners(Map.of()).items().get(0);assertThat(row.projectCount()).isEqualTo(2);assertThat(row.assignmentPeopleCount()).isEqualTo(5);assertThat(json.writeValueAsString(row)).doesNotContain("1234-5678-9012","dpstr_nm","acnt_no");
    }
}
