package com.sicost.partnersops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class PartnerLifecyclePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PartnerLifecycleService service;
    PartnerLifecycleMapper mapper;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/partnersops/PartnerLifecycleMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(PartnerLifecycleMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,PartnerLifecycleService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class);
        context.registerBean(PartnerLifecycleMapper.class,()->mapper);context.registerBean(ObjectMapper.class,()->new ObjectMapper());
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(PartnerLifecycleService.class);
        sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no,bank_cd,acnt_no,dpstr_nm) VALUES (101,'C-101','CUST','가 고객','101-00-00001',NULL,NULL,NULL),(102,'C-102','VEND','나 협력','102-00-00002','004','001-23456789','협력사 B'),(103,'C-103','BOTH','다 모두','103-00-00003',NULL,NULL,NULL),(104,'C-104','CUST','라 중지','104-00-00004',NULL,NULL,NULL)");
        sql("UPDATE mst.ptnr SET use_yn='N' WHERE ptnr_id=104");
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {if(context!=null)context.close();if(source==null)return;try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    @Test void optionsKindAndUseOrderAreReadOnly()throws Exception {
        long before=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThat(service.options("CUST")).extracting(PartnerLifecycleModels.PartnerOption::id).containsExactly("101","103");
        assertThat(service.options("VEND")).extracting(PartnerLifecycleModels.PartnerOption::id).containsExactly("102","103");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE use_yn='Y'")).isEqualTo(3);
    }
    void connections()throws Exception {
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES(201,'P-201','가 프로젝트',101,'TK','RUN'),(202,'P-202','나 프로젝트',101,'TK','PLAN'),(203,'P-203','종료',101,'TK','END')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,vndr_ptnr_id,emp_se_cd) VALUES(301,'K-301','가 개발자',102,'OS'),(302,'K-302','나 개발자',102,'OS'),(303,'K-303','다 자사',NULL,'FT')");
        sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES('A-1',201,301,'BE','2026-10-01','2026-10-22','ACTIVE'),('A-2',202,302,'BE','2026-10-22','2026-11-01','ACTIVE'),('A-3',201,301,'BE','2026-10-01','2026-10-21','ACTIVE'),('A-4',203,301,'BE','2026-10-01','2026-11-01','ACTIVE'),('A-5',201,301,'BE','2026-10-01','2026-11-01','CANCELLED'),('A-6',201,303,'BE','2026-10-01','2026-11-01','ACTIVE')");
    }
    @Test void currentAssignmentsExcludeEndedCancelledAndPastAndDoNotWrite()throws Exception {
        connections();long before=scalar("SELECT count(*) FROM asgn.asgn");
        var rows=service.assignments("102");assertThat(rows).hasSize(2);assertThat(rows).extracting(PartnerLifecycleModels.PartnerAssignment::affiliation).containsOnly("나 협력");assertThat(rows).extracting(PartnerLifecycleModels.PartnerAssignment::projectName).containsExactly("가 프로젝트","나 프로젝트");
        assertThat(service.assignments("101")).extracting(PartnerLifecycleModels.PartnerAssignment::affiliation).contains("자사");assertThat(scalar("SELECT count(*) FROM asgn.asgn")).isEqualTo(before);
    }
    @Test void historyNewestKoreanCodeNamesMaskedAccountAndDefaultActor()throws Exception {
        sql("INSERT INTO sys.data_chg_hist(tbl_nm,obj_rec_id,data_chg_ty_cd,clmn_nm,bfr_val,aft_val,chg_at) VALUES('mst.ptnr','102','UPDATE','ptnr_se_cd','CUST','VEND','2026-10-21'),('mst.ptnr','102','UPDATE','acnt_no','111122223333','444455556666','2026-10-22'),('mst.ptnr','101','UPDATE','ptnr_nm','이전','이후','2026-10-23')");
        long before=scalar("SELECT count(*) FROM sys.data_chg_hist");var rows=service.history("102");assertThat(rows).hasSize(2);assertThat(rows.get(0).itemName()).isEqualTo("계좌번호");assertThat(rows.get(0).before()).isEqualTo("3333");assertThat(rows.get(0).after()).isEqualTo("6666");assertThat(rows.get(0).actorName()).isEqualTo("기본 사용자");assertThat(rows.get(1).after()).isEqualTo("협력사");assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(before);
    }
    @Test void disableEnablePreserveConnectionsAndAuditExactlyOnce()throws Exception {
        connections();sql("UPDATE prj.prj SET prj_stts_cd='END' WHERE cust_ptnr_id=101");
        var result=service.disable(List.of("101","103","102"));assertThat(result.disabledIds()).containsExactly("101","103");assertThat(result.blocked().get(0).peopleCount()).isEqualTo(2);
        assertThat(service.options("CUST")).isEmpty();assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='DISABLE'")).isEqualTo(2);assertThat(scalar("SELECT count(*) FROM prj.prj WHERE cust_ptnr_id=101")).isEqualTo(3);assertThat(scalar("SELECT count(*) FROM asgn.asgn")).isEqualTo(6);
        service.enable("101");service.enable("101");assertThat(mapper.detail("101").ptnr_no()).isEqualTo("C-101");assertThat(service.options("CUST")).extracting(PartnerLifecycleModels.PartnerOption::id).contains("101");assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id='101' AND data_chg_ty_cd='UPDATE' AND clmn_nm='use_yn' AND bfr_val='N' AND aft_val='Y'")).isEqualTo(1);
    }
    @Test void resignationTodayDoesNotBlockButTomorrowDoes()throws Exception {
        sql("INSERT INTO mst.dev(mng_no,dev_nm,vndr_ptnr_id,emp_se_cd,rsgn_dt) VALUES('K-1','오늘',102,'OS','2026-10-22'),('K-2','내일',103,'OS','2026-10-23')");
        assertThat(service.disable(List.of("102","103")).disabledIds()).containsExactly("102");assertThat(mapper.detail("103").use_yn()).isEqualTo("Y");
    }
    @Test void missingBatchAndAuditFailureRollbackAll()throws Exception {
        assertThatThrownBy(()->service.disable(List.of("101","999"))).isInstanceOf(com.sicost.common.api.ApiException.class);assertThat(mapper.detail("101").use_yn()).isEqualTo("Y");
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_partner_history CHECK (false) NOT VALID");
        assertThatThrownBy(()->service.disable(List.of("101","103"))).isInstanceOf(RuntimeException.class);assertThat(mapper.detail("101").use_yn()).isEqualTo("Y");assertThat(mapper.detail("103").use_yn()).isEqualTo("Y");
    }
    @Test void realExportFiltersAllOrSelectedAndAccessJsonNoAccount()throws Exception {
        var file=service.export(null,"Y",null,List.of("ptnr_nm","paymentAccount"),null);
        try(var w=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file.bytes()))){assertThat(w.getSheetAt(0).getLastRowNum()).isEqualTo(6);assertThat(w.getSheetAt(0).getRow(5).getCell(2).getStringCellValue()).isEqualTo("001-23456789");}
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-15' AND user_id IS NULL AND obj_desc='거래처_20261022.xlsx' AND dtl_desc::jsonb->'columns' IS NOT NULL")).isEqualTo(1);
        assertThatThrownBy(()->service.export("CUST","Y",null,List.of("ptnr_nm"),List.of("102"))).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(1);
    }
}
