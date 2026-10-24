package com.sicost.developersops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.developersops.DeveloperLifecycleModels.*;
import com.sicost.common.api.DeveloperPaging;
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

class DeveloperLifecyclePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    DeveloperLifecycleService service;
    DeveloperLifecycleMapper mapper;
    @BeforeEach void restoreIsolatedDatabase()throws Exception{
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){s.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/developersops/DeveloperLifecycleMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(DeveloperLifecycleMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,DeveloperLifecycleService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class);
        context.registerBean(DeveloperLifecycleMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(DeveloperLifecycleService.class);
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,main_job_cd) VALUES(301,'K-0301','가 개발자','FT','BE'),(302,'K-0302','나 개발자','FT','BE'),(303,'K-0303','다 개발자','FT','BE')");
    }
    @AfterEach void cleanup()throws Exception{if(context!=null)context.close();if(source==null)return;try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){s.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
    void sql(String text)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);}}
    long scalar(String text)throws Exception{try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    void assignments()throws Exception{
        sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_nm,ptnr_se_cd,biz_reg_no) VALUES(101,'C-101','고객','CUST','101-00-00001')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES(201,'P-201','프로젝트 A',101,'TK','RUN'),(202,'P-202','프로젝트 B',101,'TK','HOLD')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES(401,'A-401',201,303,'BE','2026-10-01','2027-01-31','ACTIVE'),(402,'A-402',202,303,'BE','2026-10-02','2026-12-31','ACTIVE'),(403,'A-403',201,301,'BE','2025-01-01','2025-12-31','ENDED')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(401,'2026-10',0.50),(402,'2026-10',0.70)");
    }
    @Test void mixedLifecycleReadbackAndIdempotentAudit()throws Exception{
        assignments();var result=service.disable(List.of(301L,302L,303L));assertThat(result.disabled_ids()).containsExactly(301L,302L);assertThat(result.blocked().get(0).current_assignment_count()).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM mst.dev WHERE dev_id IN(301,302) AND use_yn='N'")).isEqualTo(2);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.dev' AND data_chg_ty_cd='DISABLE'")).isEqualTo(2);
        assertThat(service.options(null)).extracting(DeveloperOption::id).doesNotContain(301L,302L);service.disable(List.of(301L,302L));assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='DISABLE'")).isEqualTo(2);
        service.enable(301);service.enable(301);assertThat(service.options("K-0301")).hasSize(1);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id='301' AND clmn_nm='use_yn' AND data_chg_ty_cd='UPDATE'")).isEqualTo(1);
    }
    @Test void retireeEnablePreservesResignationAndHistory()throws Exception{
        sql("UPDATE mst.dev SET use_yn='N',rsgn_dt='2026-10-22' WHERE dev_id=301");sql("INSERT INTO mst.dev_work_stts_hist(dev_id,work_stts_cd,bgn_dt) VALUES(301,'RESIGN','2026-10-22')");
        service.enable(301);assertThat(service.options("K-0301")).isEmpty();assertThat(scalar("SELECT count(*) FROM mst.dev WHERE dev_id=301 AND use_yn='Y' AND rsgn_dt='2026-10-22'")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM mst.dev_work_stts_hist WHERE dev_id=301 AND work_stts_cd='RESIGN'")).isEqualTo(1);
    }
    @Test void missingBatchAndAuditFailureRollback()throws Exception{
        assertThatThrownBy(()->service.disable(List.of(301L,999L))).isInstanceOf(RuntimeException.class);assertThat(mapper.state(301).use_yn()).isEqualTo("Y");
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_developer_history CHECK(false) NOT VALID");assertThatThrownBy(()->service.disable(List.of(301L,302L))).isInstanceOf(RuntimeException.class);assertThat(mapper.state(301).use_yn()).isEqualTo("Y");assertThat(mapper.state(302).use_yn()).isEqualTo("Y");
    }
    @Test void assignmentsKeepPastAndExportDoesNotMultiplyMm()throws Exception{
        assignments();assertThat(service.assignments(301,new DeveloperPaging(0,50,null),null).items()).hasSize(1);
        var q=new DeveloperExportQuery(null,null,null,"K-0303","Y",LocalDate.of(2026,10,22));var row=mapper.exportRows(q).get(0);assertThat(row.get("current_month_mm")).isEqualTo(new java.math.BigDecimal("1.20"));assertThat(row.get("current_assignment_count")).isEqualTo(2L);assertThat(row.get("status")).isEqualTo("투입중");assertThat(row.get("current_project_name")).isEqualTo("프로젝트 A");
    }
    @Test void deletedWorkStatusSnapshotsStayWithCorrectDeveloper()throws Exception{
        sql("INSERT INTO sys.data_chg_hist(tbl_nm,obj_rec_id,data_chg_ty_cd,bfr_val) VALUES('mst.dev_work_stts_hist','999','DELETE','{\"work_stts_hist_id\":999,\"dev_id\":301}'),('mst.dev_work_stts_hist','998','DELETE','legacy plain text'),('mst.dev_work_stts_hist','997','DELETE','{\"dev_id\":302}')");
        assertThat(service.history(301,new DeveloperPaging(0,50,null),null).items()).extracting(DeveloperChange::record_id).containsExactly("999");assertThat(service.history(303,new DeveloperPaging(0,50,null),null).total()).isZero();
    }
    @Test void calendarWindowReadsDatabaseGeneratedAmounts()throws Exception{
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt,alwnc_amt,sins_cmpny_amt,rtrpay_acml_amt) VALUES(301,'2026-10','FT',4000000,100000,200000,300000),(301,'2025-10','FT',1,0,0,0),(301,'2026-11','FT',2,0,0,0)");
        var page=service.monthlyCosts(301,new DeveloperPaging(0,50,null),null,null);assertThat(page.total()).isEqualTo(1);assertThat(page.items().get(0).indv_lbr_cst_amt()).isEqualTo(4600000);assertThat(mapper.latestSalary(301).month()).isEqualTo("2026-11");
    }
}
