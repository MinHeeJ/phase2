package com.sicost.partnerdetail;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.partners.*;
import com.sicost.partners.PartnerModels.PartnerSave;
import com.sicost.partnersops.*;
import com.sicost.partnersops.PartnerLifecycleModels.*;
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

class PartnerDetailPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PartnerService partners;
    PartnerLifecycleService lifecycle;
    String customer,vendor,both;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(
            new ClassPathResource("mappers/partners/PartnerMapper.xml"),new ClassPathResource("mappers/partnersops/PartnerLifecycleMapper.xml"));factory.afterPropertiesSet();
        var session=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,PartnerService.class,PartnerLifecycleService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class);
        context.registerBean(PartnerMapper.class,()->session.getMapper(PartnerMapper.class));
        context.registerBean(PartnerLifecycleMapper.class,()->session.getMapper(PartnerLifecycleMapper.class));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper());
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();partners=context.getBean(PartnerService.class);lifecycle=context.getBean(PartnerLifecycleService.class);
        customer=partners.create(input("CUST","고객사 A","123-45-67890")).ptnr_id();
        vendor=partners.create(input("VEND","협력사 B","222-22-22222")).ptnr_id();
        both=partners.create(input("BOTH","둘 다","333-33-33333")).ptnr_id();
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    PartnerSave input(String kind,String name,String biz) {
        return new PartnerSave(kind,name,biz,"대표자","SI","담당자","010-1234-5678","비고",kind.equals("VEND")?null:"tax@company.co.kr",
            kind.equals("CUST")?null:"004",kind.equals("CUST")?null:"001-23456789",kind.equals("CUST")?null:"협력사 B");
    }
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    String snapshot()throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT json_build_object('partners',(SELECT json_agg(p ORDER BY ptnr_id) FROM mst.ptnr p),'assignments',(SELECT json_agg(a ORDER BY asgn_id) FROM asgn.asgn a),'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),'access',(SELECT json_agg(x ORDER BY acs_hist_id) FROM sys.acs_hist x))::text")) {
            assertThat(r.next()).isTrue();return r.getString(1);
        }
    }
    void connections()throws Exception {
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES(201,'P-201','가 프로젝트',"+customer+",'TK','RUN'),(202,'P-202','나 프로젝트',"+customer+",'TK','PLAN'),(203,'P-203','종료',"+customer+",'TK','END'),(204,'P-204','보류',"+both+",'TK','HOLD')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,vndr_ptnr_id,emp_se_cd) VALUES(301,'K-301','가 개발자',"+vendor+",'OS'),(302,'K-302','나 개발자',"+vendor+",'OS'),(303,'K-303','다 자사',NULL,'FT'),(304,'K-304','라 모두',"+both+",'OS')");
        sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES('A-1',201,301,'BE','2026-10-01','2026-10-22','ACTIVE'),('A-2',202,302,'BE','2026-10-22','2026-11-01','ACTIVE'),('A-3',201,301,'BE','2026-10-01','2026-10-21','ACTIVE'),('A-4',203,301,'BE','2026-10-01','2026-11-01','ACTIVE'),('A-5',201,301,'BE','2026-10-01','2026-11-01','CANCELLED'),('A-6',201,303,'BE','2026-10-01','2026-11-01','ACTIVE'),('A-7',201,301,'BE','2026-10-23','2026-11-01','ACTIVE'),('A-8',204,304,'BE','2026-10-01','2026-11-01','ACTIVE'),('A-9',201,304,'BE','2026-10-01','2026-11-01','ACTIVE')");
    }
    @Test void detailProjectsFieldsByKindAndReadsNeverChangeDatabase()throws Exception {
        sql("UPDATE mst.ptnr SET use_yn='N' WHERE ptnr_id="+vendor);
        String before=snapshot();
        var c=partners.get(Long.parseLong(customer));var v=partners.get(Long.parseLong(vendor));var b=partners.get(Long.parseLong(both));
        assertThat(c.bank_cd()).isNull();assertThat(c.acnt_no()).isNull();assertThat(c.dpstr_nm()).isNull();
        assertThat(v.txinv_email_addr()).isNull();assertThat(v.use_yn()).isEqualTo("N");assertThat(v.acnt_no()).isEqualTo("001-23456789");
        assertThat(b.txinv_email_addr()).isEqualTo("tax@company.co.kr");assertThat(b.acnt_no()).isEqualTo("001-23456789");
        partners.get(Long.parseLong(both));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void vendorAssignmentsExcludeYesterdayEndedCancelledAndFutureAndIncludeBothDateBoundaries()throws Exception {
        connections();String before=snapshot();var rows=lifecycle.assignments(vendor);
        assertThat(rows).extracting(PartnerAssignment::projectName).containsExactly("가 프로젝트","나 프로젝트");
        assertThat(rows).extracting(PartnerAssignment::affiliation).containsOnly("협력사 B");
        assertThat(rows.get(0).endDate()).isEqualTo(LocalDate.of(2026,10,22));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void customerAssignmentsSortByProjectThenNameAndNullAffiliationIsOwnCompany()throws Exception {
        connections();var rows=lifecycle.assignments(customer);
        assertThat(rows).extracting(PartnerAssignment::developerName).containsExactly("가 개발자","다 자사","라 모두","나 개발자");
        assertThat(rows.get(1).affiliation()).isEqualTo("자사");assertThat(rows.get(0).jobName()).isNotBlank();
    }
    @Test void bothScopeUnionsCustomerAndVendorWithoutDuplicatingTheOverlappingAssignment()throws Exception {
        connections();var rows=lifecycle.assignments(both);
        assertThat(rows).extracting(PartnerAssignment::projectName).containsExactly("가 프로젝트","보류");
    }
    @Test void historyScopesByTableAndPrimaryKeyAndMasksAccountsAndUsesKoreanNames()throws Exception {
        sql("INSERT INTO sys.data_chg_hist(tbl_nm,obj_rec_id,data_chg_ty_cd,clmn_nm,bfr_val,aft_val,chg_at) VALUES('mst.ptnr','"+vendor+"','UPDATE','ptnr_se_cd','CUST','VEND','2026-10-21'),('mst.ptnr','"+vendor+"','UPDATE','acnt_no','111122223333','444455556666','2026-10-22'),('mst.ptnr','"+customer+"','UPDATE','ptnr_nm','이전','다른 고객','2026-10-23'),('mst.dev','"+vendor+"','UPDATE','dev_nm','이전','다른 테이블','2026-10-24')");
        String before=snapshot();var rows=lifecycle.history(vendor);
        // Creation history is retained as well; fixed update dates precede the database's current timestamp.
        var updates=rows.stream().filter(r->r.changeType().equals("UPDATE")).toList();
        assertThat(updates).hasSize(2);assertThat(updates.get(0).itemName()).isEqualTo("계좌번호");
        assertThat(updates.get(0).before()).isEqualTo("3333");assertThat(updates.get(0).after()).isEqualTo("6666");
        assertThat(updates.get(0).actorName()).isEqualTo("기본 사용자");assertThat(updates.get(1).after()).isEqualTo("협력사");
        assertThat(rows).allSatisfy(r->assertThat(r.after()).isNotIn("다른 고객","다른 테이블","444455556666"));
        lifecycle.history(vendor);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void noCurrentAssignmentsReturnsEmptyAndMissingPartnerNeverChangesAnything()throws Exception {
        String before=snapshot();assertThat(lifecycle.assignments(vendor)).isEmpty();
        assertThatThrownBy(()->partners.get(999)).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThatThrownBy(()->lifecycle.assignments("999")).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThatThrownBy(()->lifecycle.history("999")).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
}
