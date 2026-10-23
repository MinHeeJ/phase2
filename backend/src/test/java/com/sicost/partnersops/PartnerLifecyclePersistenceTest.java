package com.sicost.partnersops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.partnersops.PartnerLifecycleModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;
import org.springframework.util.LinkedMultiValueMap;

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
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/partners/PartnerLifecycleMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(PartnerLifecycleMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,PartnerLifecycleService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class);
        context.registerBean(PartnerLifecycleMapper.class,()->mapper);context.registerBean(ObjectMapper.class,()->new ObjectMapper());context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(PartnerLifecycleService.class);
        sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_nm,ptnr_se_cd,biz_reg_no,bank_cd,acnt_no,dpstr_nm,use_yn) VALUES (9001,'C-9001','고객 A','CUST','900-00-00001',NULL,NULL,NULL,'Y'),(9002,'C-9002','협력사 B','VEND','900-00-00002','KB','001-2345-6789','예금주','Y'),(9003,'C-9003','둘 다','BOTH','900-00-00003','KB','001-2345-6790','예금주','Y'),(9004,'C-9004','중지','CUST','900-00-00004',NULL,NULL,NULL,'N')");
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    void assignments()throws Exception {
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,vndr_ptnr_id,emp_se_cd) VALUES (9001,'K-9001','가',9002,'OS'),(9002,'K-9002','나',9002,'OS'),(9003,'K-9003','다',NULL,'FT')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES (9001,'P-9001','A 프로젝트',9001,'MO','RUN'),(9002,'P-9002','B 프로젝트',9001,'MO','HOLD'),(9003,'P-9003','종료 프로젝트',9001,'MO','END')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES (9001,'A-9001',9001,9001,'BE','2026-10-01','2026-10-31','ACTIVE'),(9002,'A-9002',9002,9002,'BE','2026-10-01','2026-10-31','ACTIVE'),(9003,'A-9003',9001,9003,'BE','2026-10-22','2026-10-22','ACTIVE'),(9004,'A-9004',9001,9001,'BE','2026-10-01','2026-10-21','ACTIVE'),(9005,'A-9005',9003,9001,'BE','2026-10-01','2026-10-31','ACTIVE'),(9006,'A-9006',9002,9001,'BE','2026-10-01','2026-10-31','CANCELLED')");
    }
    @Test void optionsFilterSortAndReadHasNoSideEffects()throws Exception {
        long before=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThat(service.listPartnerOptions("CUST")).extracting(PartnerOption::id).containsExactly("9001","9003");
        assertThat(service.listPartnerOptions("VEND")).extracting(PartnerOption::id).containsExactly("9003","9002");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(4);
    }
    @Test void currentAssignmentsExcludeYesterdayEndedAndCancelledAndKeepAffiliation()throws Exception {
        assignments();long count=scalar("SELECT count(*) FROM asgn.asgn");
        assertThat(service.listPartnerAssignments("9002")).hasSize(2).extracting(PartnerAssignment::affiliation).containsOnly("협력사 B");
        assertThat(service.listPartnerAssignments("9001")).hasSize(3).extracting(PartnerAssignment::affiliation).contains("자사");
        assertThat(scalar("SELECT count(*) FROM asgn.asgn")).isEqualTo(count);
    }
    @Test void historyIsLatestFirstTranslatedMaskedAndReadOnly()throws Exception {
        sql("INSERT INTO sys.data_chg_hist(tbl_nm,obj_rec_id,data_chg_ty_cd,clmn_nm,bfr_val,aft_val,chg_at) VALUES ('mst.ptnr','9002','UPDATE','bank_cd','KB','SHINHAN','2026-10-21'),('mst.ptnr','9002','UPDATE','acnt_no','001-2345-6789','009-8765-4321','2026-10-22')");
        var history=service.listPartnerHistory("9002");assertThat(history).hasSize(2);assertThat(history.get(0).itemName()).isEqualTo("계좌번호");assertThat(history.get(0).before()).isEqualTo("6789");assertThat(history.get(0).after()).isEqualTo("4321");assertThat(history.get(0).actorName()).isEqualTo("기본 사용자");assertThat(history.get(1).before()).isEqualTo("국민은행");assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(2);
    }
    @Test void disablePartialBatchRechecksEmploymentAndPreservesForeignKeys()throws Exception {
        assignments();sql("INSERT INTO mst.dev(mng_no,dev_nm,vndr_ptnr_id,emp_se_cd) VALUES ('K-9004','라',9002,'OS')");
        var result=service.savePartnerDisable(List.of(9002L,9003L,9004L));assertThat(result.disabledIds()).containsExactly("9003");assertThat(result.blocked().get(0).peopleCount()).isEqualTo(3);
        assertThat(mapper.state(9002L).useYn()).isEqualTo("Y");assertThat(mapper.state(9003L).useYn()).isEqualTo("N");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='DISABLE'")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM asgn.asgn")).isEqualTo(6);assertThat(scalar("SELECT cust_ptnr_id FROM prj.prj WHERE prj_id=9001")).isEqualTo(9001);
        assertThatThrownBy(()->service.savePartnerDisable(List.of(9002L))).isInstanceOf(com.sicost.common.api.ApiException.class);
    }
    @Test void retiredTodayDoesNotBlockAndEnablePreservesIdentityAndOptions()throws Exception {
        sql("INSERT INTO mst.dev(mng_no,dev_nm,vndr_ptnr_id,emp_se_cd,rsgn_dt) VALUES ('K-9010','퇴사',9003,'OS','2026-10-22')");
        service.savePartnerDisable(List.of(9001L,9003L));assertThat(service.listPartnerOptions("CUST")).isEmpty();
        var detail=service.savePartnerEnable("9003");assertThat(detail.get("ptnr_id")).isEqualTo("9003");assertThat(detail.get("ptnr_no")).isEqualTo("C-9003");assertThat(service.listPartnerOptions("CUST")).extracting(PartnerOption::id).containsExactly("9003");
        service.savePartnerEnable("9003");assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE' AND bfr_val='N' AND aft_val='Y'")).isEqualTo(1);
    }
    @Test void missingIdAndAuditFailureRollbackEveryStatus()throws Exception {
        assertThatThrownBy(()->service.savePartnerDisable(List.of(9001L,9999L))).isInstanceOf(com.sicost.common.api.ApiException.class);assertThat(mapper.state(9001L).useYn()).isEqualTo("Y");
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK (false) NOT VALID");
        assertThatThrownBy(()->service.savePartnerDisable(List.of(9001L,9003L))).isInstanceOf(RuntimeException.class);assertThat(mapper.state(9001L).useYn()).isEqualTo("Y");assertThat(mapper.state(9003L).useYn()).isEqualTo("Y");
    }
    @Test void exportSearchUsesTheSameLiteralNameAndFullBusinessNumberAsTheList()throws Exception {
        sql("UPDATE mst.ptnr SET ptnr_nm='고객 % 회사' WHERE ptnr_id=9001");
        var p=new LinkedMultiValueMap<String,String>();p.add("columns","ptnr_nm");p.add("kind","");
        p.set("q"," 9000000001 ");
        assertThat(mapper.exportRows(PartnerExportQuery.from(p,LocalDate.of(2026,10,22))))
            .extracting(r->r.get("ptnr_id")).containsExactly("9001");
        p.set("q","90000");
        assertThat(mapper.exportRows(PartnerExportQuery.from(p,LocalDate.of(2026,10,22)))).isEmpty();
        p.set("q","%");
        assertThat(mapper.exportRows(PartnerExportQuery.from(p,LocalDate.of(2026,10,22))))
            .extracting(r->r.get("ptnr_id")).containsExactly("9001");
    }
    @Test void exportFiltersIntersectSelectionAndLogsOnlyOneDownload()throws Exception {
        var p=new LinkedMultiValueMap<String,String>();p.add("columns","ptnr_nm,paymentAccount");p.add("kind","VEND");p.add("ids","9001,9002");
        var q=PartnerExportQuery.from(p,LocalDate.of(2026,10,22));assertThat(mapper.exportRows(q)).hasSize(1);
        var file=service.getPartnerExport(q);try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file.bytes()))) {assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(4);assertThat(book.getSheetAt(0).getRow(4).getCell(2).getStringCellValue()).isEqualTo("001-2345-6789");}
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-15' AND obj_desc='거래처_20261022.xlsx' AND user_id IS NULL AND dtl_desc::jsonb->>'rowCount'='1'")).isEqualTo(1);
    }
}
