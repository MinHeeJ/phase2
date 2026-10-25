package com.sicost.developers;

import static com.sicost.developers.DeveloperModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class DeveloperPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    DeveloperService service;
    DeveloperMapper mapper;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/developers/DeveloperMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(DeveloperMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,DeveloperService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(DeveloperMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T15:30:00Z"),ZoneOffset.UTC));context.refresh();service=context.getBean(DeveloperService.class);
    }
    @AfterEach void cleanup()throws Exception {if(context!=null)context.close();if(source==null)return;try(Connection c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){s.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    String value(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getString(1);}}
    DeveloperSave input(String name) {return DeveloperInput.parse(json.valueToTree(Map.of("dev_nm",name,"emp_se_cd","FT","main_job_cd","BE","sub_job",List.of(),"skill",List.of())),false);}
    DeveloperSave edit(long id,String key,Object value) {
        var row=mapper.row(id);ObjectNode n=json.valueToTree(row.input(mapper.subJobs(id),mapper.skills(id)));n.set(key,json.valueToTree(value));return DeveloperInput.parse(n,true);
    }
    PageResult<DeveloperListItem> list(String use,String status,String emp,String job,String q) {return service.list(new DeveloperPaging(0,50,null),new DeveloperQuery.Filters(emp,job,status,q,use));}
    @Test void createFinalNumberChildrenAndOneAuditAreReadable()throws Exception {
        ObjectNode n=json.valueToTree(input("등록"));n.set("sub_job",json.valueToTree(List.of("BE","FE","UX")));n.set("skill",json.valueToTree(List.of("JAVA","REACT")));
        var created=service.create(DeveloperInput.parse(n,false));assertThat(created.item().dev_id()).isEqualTo(1);assertThat(created.item().mng_no()).isEqualTo("K-0001");assertThat(created.item().use_yn()).isEqualTo("Y");
        assertThat(service.get(1)).isEqualTo(created);assertThat(scalar("SELECT count(*) FROM mst.dev_sub_job WHERE dev_id=1")).isEqualTo(2);assertThat(scalar("SELECT count(*) FROM mst.dev_skl WHERE dev_id=1")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.dev' AND obj_rec_id='1' AND data_chg_ty_cd='INSERT'")).isEqualTo(1);
    }
    @Test void manualReservationCollisionRollsBackAndDoesNotTruncateFiveDigits()throws Exception {
        ObjectNode n=json.valueToTree(input("선점"));n.put("mng_no","K-0002");service.create(DeveloperInput.parse(n,false));
        assertThatThrownBy(()->service.create(input("충돌"))).isInstanceOf(ApiException.class).hasMessage("이미 있는 관리번호입니다.");assertThat(scalar("SELECT count(*) FROM mst.dev")).isEqualTo(1);
        sql("SELECT setval('mst.seq_dev_id',10000,false)");assertThat(service.create(input("큰 번호")).item().mng_no()).isEqualTo("K-10000");
    }
    @Test void combinedEmployeeMainOrSubJobAndLiteralNumberSearch()throws Exception {
        sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(1,'C-001','VEND','협력사','123-45-67890')");
        service.create(input("정규"));service.create(input("주 직무"));service.create(input("보조 직무"));service.create(input("제외"));
        sql("UPDATE mst.dev SET emp_se_cd='OS',vndr_ptnr_id=1 WHERE dev_id IN(2,3,4); UPDATE mst.dev SET main_job_cd='FE' WHERE dev_id IN(3,4); INSERT INTO mst.dev_sub_job(dev_id,job_cd) VALUES(3,'BE')");
        assertThat(list("Y",null,"OS","BE",null).items()).extracting(DeveloperListItem::dev_id).containsExactlyInAnyOrder(2L,3L);assertThat(list("Y",null,"OS","BE",null).total()).isEqualTo(2);
        assertThat(list("Y",null,"OS","BE","K-0003").items()).extracting(DeveloperListItem::dev_id).containsExactly(3L);
    }
    void project()throws Exception {sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(1,'C-001','CUST','고객','123-45-67890'); INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES(1,'P1','프로젝트 A',1,'MO','RUN'),(2,'P2','프로젝트 B',1,'MO','PLAN'),(3,'P3','종료',1,'MO','END')");}
    @Test void injectedSeoulClockHasRetirementLeaveWithdrawalActiveAndIdlePriority()throws Exception {
        for(String name:List.of("퇴사","휴직","철수","투입","대기"))service.create(input(name));project();
        sql("UPDATE mst.dev SET rsgn_dt='2026-10-23' WHERE dev_id=1; INSERT INTO mst.dev_work_stts_hist(dev_id,work_stts_cd,bgn_dt,end_dt) VALUES(1,'LEAVE','2026-10-01',NULL),(2,'PARENTAL','2026-10-23','2026-10-23')");
        sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES('A1',1,2,'BE','2026-10-01','2027-03-31'),('A2',1,3,'BE','2026-10-01','2026-11-30'),('A3',1,4,'BE','2026-10-01','2027-03-31')");
        assertThat(service.get(1).item().status()).isEqualTo("퇴사");assertThat(service.get(2).item().status()).isEqualTo("휴직");assertThat(service.get(3).item().status()).isEqualTo("철수예정");assertThat(service.get(4).item().status()).isEqualTo("투입중");assertThat(service.get(5).item().status()).isEqualTo("대기");
        assertThat(list(null,null,null,null,null).total()).isEqualTo(4);assertThat(list(null,"퇴사",null,null,null).items()).extracting(DeveloperListItem::dev_id).containsExactly(1L);
    }
    @Test void currentProjectsAndAllNonCancelledMonthMmAreIndependent()throws Exception {
        service.create(input("집계"));project();
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES(1,'A1',1,1,'BE','2026-09-01','2026-11-30','ACTIVE'),(2,'A2',2,1,'BE','2026-10-01','2027-03-31','ACTIVE'),(3,'A3',3,1,'BE','2026-01-01','2026-09-01','ENDED'),(4,'A4',1,1,'BE','2026-10-01','2026-11-30','CANCELLED')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(1,'2026-10',0.50),(2,'2026-10',0.70),(3,'2026-10',0.20),(4,'2026-10',9.00),(1,'2026-09',4.00)");
        var row=service.get(1).item();assertThat(row.current_project_name()).isEqualTo("프로젝트 A");assertThat(row.current_assignment_count()).isEqualTo(2);assertThat(row.current_month_mm()).isEqualByComparingTo("1.40");assertThat(row.over_one_mm()).isTrue();assertThat(row.latest_assignment_end_dt()).isEqualTo(LocalDate.of(2027,3,31));assertThat(row.status()).isEqualTo("투입중");
        sql("DELETE FROM asgn.asgn_mth WHERE asgn_id=3");assertThat(service.get(1).item().current_month_mm()).isEqualByComparingTo("1.20");
    }
    @Test void paginationFiltersBeforeBoundaryAndTotal()throws Exception {
        sql("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,main_job_cd) SELECT 'TEST-'||n,lpad(n::text,3,'0'),'FT','BE' FROM generate_series(1,105) n; INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,main_job_cd,use_yn) VALUES('EXCLUDED','000','FT','BE','N')");
        var f=new DeveloperQuery.Filters("FT","BE",null,null,"Y");var first=service.list(new DeveloperPaging(0,50,null),f);var second=service.list(new DeveloperPaging(1,50,null),f);var last=service.list(new DeveloperPaging(2,50,null),f);
        assertThat(first.total()).isEqualTo(105);assertThat(first.items()).hasSize(50);assertThat(first.items().get(49).dev_nm()).isEqualTo("050");assertThat(second.items().get(0).dev_nm()).isEqualTo("051");assertThat(last.items()).hasSize(5);assertThat(last.total()).isEqualTo(105);
    }
    @Test void disabledRetireesAndIndependentLatestSalaryAreReadable()throws Exception {
        service.create(input("퇴사중지"));service.create(input("무급여"));sql("UPDATE mst.dev SET use_yn='N',rsgn_dt='2026-10-01' WHERE dev_id=1; INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(1,'2026-10','FT',100),(1,'2027-02','FT',200)");
        assertThat(list("N",null,null,null,null).items()).extracting(DeveloperListItem::dev_id).containsExactly(1L);assertThat(service.get(1).item().rsgn_dt()).isEqualTo(LocalDate.of(2026,10,1));assertThat(service.get(1).latest_salary()).isEqualTo(new LatestSalary("2027-02",200));assertThat(service.get(2).latest_salary()).isNull();
    }
    @Test void updateOnlyGradeAuditsExactlyOneColumnAndPreservesMonthlySnapshot()throws Exception {
        service.create(input("수정"));sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(1,'2026-10','FT',4000000)");
        assertThat(service.update(1,edit(1,"grd_cd","SR")).changed()).isTrue();assertThat(service.get(1).item().grd_cd()).isEqualTo("SR");assertThat(list(null,null,null,null,null).items().get(0).grade_name()).isEqualTo("고급");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.dev' AND obj_rec_id='1' AND data_chg_ty_cd='UPDATE' AND clmn_nm='grd_cd'")).isEqualTo(1);
        service.update(1,edit(1,"emp_se_cd","CT"));assertThat(value("SELECT emp_se_cd FROM cst.mth_cst WHERE dev_id=1")).isEqualTo("FT");
    }
    @Test void identityOnlyAndReorderedSetsHaveZeroDml()throws Exception {
        service.create(input("무변경"));service.update(1,edit(1,"sub_job",List.of("FE","UX")));var before=service.get(1);String timestamp=value("SELECT updated_at::text FROM mst.dev WHERE dev_id=1");long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        ObjectNode n=json.valueToTree(mapper.row(1).input(mapper.subJobs(1),mapper.skills(1)));n.put("mng_no","not valid !");n.set("sub_job",json.valueToTree(List.of("UX","FE")));
        assertThat(service.update(1,DeveloperInput.parse(n,true)).changed()).isFalse();assertThat(service.get(1)).isEqualTo(before);assertThat(value("SELECT updated_at::text FROM mst.dev WHERE dev_id=1")).isEqualTo(timestamp);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void codeNamesRemainAfterDeactivationAndUnchangedCodesAreRetained()throws Exception {
        service.create(input("이전 코드"));sql("UPDATE cmn.cd_dtl SET use_yn='N' WHERE cd_grp_id='JOB' AND dtl_cd='BE'");assertThat(service.get(1).item().main_job_name()).isEqualTo("백엔드 개발");assertThat(service.update(1,edit(1,"dev_nm","변경")).changed()).isTrue();
        assertThatThrownBy(()->service.create(input("신규"))).isInstanceOf(ApiException.class);
    }
    @Test void childAndAuditFailureRollBackEntireCreate()throws Exception {
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        ObjectNode n=json.valueToTree(input("롤백"));n.set("skill",json.valueToTree(List.of("JAVA")));
        assertThatThrownBy(()->service.create(DeveloperInput.parse(n,false))).isInstanceOf(RuntimeException.class);assertThat(scalar("SELECT count(*) FROM mst.dev")).isZero();assertThat(scalar("SELECT count(*) FROM mst.dev_skl")).isZero();assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isZero();
    }
    @Test void auditFailureRollsBackScalarAndChildrenUpdate()throws Exception {
        service.create(input("이전"));sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.update(1,edit(1,"skill",List.of("JAVA")))).isInstanceOf(RuntimeException.class);assertThat(mapper.skills(1)).isEmpty();assertThat(service.get(1).item().dev_nm()).isEqualTo("이전");assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(1);
    }
}
