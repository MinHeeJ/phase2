package com.sicost.salespartsops;

import static com.sicost.salespartsops.SalesPartLifecycleModels.*;
import static com.sicost.salesparts.SalesPartModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.salesparts.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class SalesPartLifecyclePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    SalesPartService parts;
    SalesPartLifecycleService service;
    SalesPartExportService exports;
    SalesPartLifecycleMapper mapper;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){s.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/salesparts/SalesPartMapper.xml"),new ClassPathResource("mappers/salespartsops/SalesPartLifecycleMapper.xml"));factory.afterPropertiesSet();
        var template=new SqlSessionTemplate(factory.getObject());mapper=template.getMapper(SalesPartLifecycleMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,SalesPartService.class,SalesPartLifecycleService.class,SalesPartExportService.class,ExcelExportService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(SalesPartMapper.class,()->template.getMapper(SalesPartMapper.class));context.registerBean(SalesPartLifecycleMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper());
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();
        parts=context.getBean(SalesPartService.class);service=context.getBean(SalesPartLifecycleService.class);exports=context.getBean(SalesPartExportService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){s.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    String text(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getString(1);}}
    void execute(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String create(String name)throws Exception {return parts.create(new SalesPartCreate(name,"2026-10","파트장",new BigDecimal("40"),1500000)).bsn_part_id();}
    StandardSave standard(String month,String rate) {return new StandardSave(month,"파트장",new BigDecimal(rate),1500000);}
    void january(String id)throws Exception {execute("UPDATE mst.bsn_part_std_hist SET aply_bgn_ym='2026-01' WHERE bsn_part_id="+id);}
    @Test void optionsReadOnlyOrderedWithHeadquartersAndStateRoundTrip()throws Exception {
        String id=create("영업 파트"),other=create("중지 파트");service.disable(other);
        long history=scalar("SELECT count(*) FROM sys.data_chg_hist"),access=scalar("SELECT count(*) FROM sys.acs_hist"),count=scalar("SELECT count(*) FROM mst.bsn_part");
        var before=service.options();assertThat(before).extracting(SalesPartOption::id).contains(id).doesNotContain(other);assertThat(before.get(0).hq()).isTrue();
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access);assertThat(scalar("SELECT count(*) FROM mst.bsn_part")).isEqualTo(count);
        var detail=parts.get(id);service.disable(id);assertThat(service.options()).extracting(SalesPartOption::id).doesNotContain(id);service.enable(id);assertThat(service.options()).containsExactlyElementsOf(before);
        assertThat(parts.get(id).standards()).isEqualTo(detail.standards());assertThat(parts.get(id).bsn_part_id()).isEqualTo(id);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id='"+id+"' AND tbl_nm='mst.bsn_part' AND data_chg_ty_cd='DISABLE'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id='"+id+"' AND tbl_nm='mst.bsn_part' AND data_chg_ty_cd='UPDATE' AND clmn_nm='use_yn' AND bfr_val='N' AND aft_val='Y'")).isEqualTo(1);
    }
    @Test void disableAuditFailureRollsBackStateAndPreservesOptionsAndStandards()throws Exception {
        String id=create("중지 원자성 파트");
        var before=parts.get(id);
        String parent=text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id);
        long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_part_disable CHECK (NOT (tbl_nm='mst.bsn_part' AND data_chg_ty_cd='DISABLE')) NOT VALID");
        assertThatThrownBy(()->service.disable(id)).isInstanceOf(RuntimeException.class);
        assertThat(parts.get(id)).isEqualTo(before);
        assertThat(text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id)).isEqualTo(parent);
        assertThat(service.options()).extracting(SalesPartOption::id).contains(id);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void repeatedDisablePreservesUpdatedTimestampAndExactlyOnePartPkAudit()throws Exception {
        String id=create("중지 반복 파트");
        var standards=parts.get(id).standards();
        service.disable(id);
        String parent=text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id);
        long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        service.disable(id);
        assertThat(text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id)).isEqualTo(parent);
        assertThat(parts.get(id).standards()).isEqualTo(standards);
        assertThat(parts.get(id).use_yn()).isEqualTo("N");
        assertThat(service.options()).extracting(SalesPartOption::id).doesNotContain(id);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.bsn_part' AND obj_rec_id='"+id+"' AND data_chg_ty_cd='DISABLE' AND clmn_nm='use_yn' AND bfr_val='Y' AND aft_val='N' AND chg_user_id IS NULL")).isEqualTo(1);
    }
    @Test void enablePreservesLinkedRowsAndStandardsAndRecordsOneUpdate()throws Exception {
        String id=create("영업 파트");service.disable(id);
        execute("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,bsn_part_id) VALUES('ENABLE1','소속 인력','FT',"+id+")");
        execute("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES('ENABLE1','진행 프로젝트','TK','RUN',"+id+")");
        var before=parts.get(id);
        String developer=text("SELECT row_to_json(d)::text FROM mst.dev d WHERE mng_no='ENABLE1'");
        String project=text("SELECT row_to_json(p)::text FROM prj.prj p WHERE prj_no='ENABLE1'");
        long count=scalar("SELECT count(*) FROM mst.bsn_part"),audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThat(service.options()).extracting(SalesPartOption::id).doesNotContain(id);
        var enabled=service.enable(id);
        assertThat(enabled.bsn_part_id()).isEqualTo(id);
        assertThat(enabled.bsn_part_nm()).isEqualTo(before.bsn_part_nm());
        assertThat(enabled.use_yn()).isEqualTo("Y");
        assertThat(enabled.standards()).isEqualTo(before.standards());
        assertThat(enabled.peopleCount()).isEqualTo(1);assertThat(enabled.projectCount()).isEqualTo(1);
        assertThat(service.options()).contains(new SalesPartOption(id,before.bsn_part_nm(),false));
        assertThat(text("SELECT row_to_json(d)::text FROM mst.dev d WHERE mng_no='ENABLE1'")).isEqualTo(developer);
        assertThat(text("SELECT row_to_json(p)::text FROM prj.prj p WHERE prj_no='ENABLE1'")).isEqualTo(project);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part")).isEqualTo(count);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits+1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.bsn_part' AND obj_rec_id='"+id+"' AND data_chg_ty_cd='UPDATE' AND clmn_nm='use_yn' AND bfr_val='N' AND aft_val='Y' AND chg_user_id IS NULL")).isEqualTo(1);
    }
    @Test void repeatedEnablePreservesParentTimestampAndAuditCount()throws Exception {
        String id=create("재사용 파트");service.disable(id);service.enable(id);
        String parent=text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id);
        long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        var before=parts.get(id);
        assertThat(service.enable(id)).isEqualTo(before);
        assertThat(text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id)).isEqualTo(parent);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void enableAuditFailureRollsBackStateAndOptionsThenAllowsRetry()throws Exception {
        String id=create("재사용 원자성 파트");service.disable(id);
        String parent=text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id);
        var before=parts.get(id);long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_part_enable CHECK (NOT (tbl_nm='mst.bsn_part' AND data_chg_ty_cd='UPDATE' AND clmn_nm='use_yn' AND aft_val='Y')) NOT VALID");
        assertThatThrownBy(()->service.enable(id)).isInstanceOf(RuntimeException.class);
        assertThat(parts.get(id)).isEqualTo(before);
        assertThat(text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id)).isEqualTo(parent);
        assertThat(service.options()).extracting(SalesPartOption::id).doesNotContain(id);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
        execute("ALTER TABLE sys.data_chg_hist DROP CONSTRAINT reject_part_enable");
        assertThat(service.enable(id).use_yn()).isEqualTo("Y");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits+1);
    }
    @Test void concurrentEnableSerializesAndAuditsOnlyOneTransition()throws Exception {
        String id=create("동시 재사용 파트");service.disable(id);
        long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{start.await();return service.enable(id);});
            var second=pool.submit(()->{start.await();return service.enable(id);});start.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).use_yn()).isEqualTo("Y");
            assertThat(second.get(15,TimeUnit.SECONDS).use_yn()).isEqualTo("Y");
            assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits+1);
        } finally {pool.shutdownNow();}
    }
    @Test void missingEnableNeverCreatesPartOrHistory()throws Exception {
        long count=scalar("SELECT count(*) FROM mst.bsn_part"),audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.enable("9223372036854775807")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part")).isEqualTo(count);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void renameOnlyParentAndLinkedQueriesReadNewName()throws Exception {
        String id=create("영업 파트");execute("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,bsn_part_id) VALUES('NAME1','소속 인력','FT',"+id+")");
        execute("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES('NAME1','지난 프로젝트','TK','END',"+id+")");
        String children=text("SELECT row_to_json(d)::text FROM mst.dev d WHERE mng_no='NAME1'"),project=text("SELECT row_to_json(p)::text FROM prj.prj p WHERE prj_no='NAME1'");
        service.rename(id,"새 이름");assertThat(parts.get(id).bsn_part_nm()).isEqualTo("새 이름");assertThat(text("SELECT p.bsn_part_nm FROM mst.dev d JOIN mst.bsn_part p USING(bsn_part_id) WHERE d.mng_no='NAME1'")).isEqualTo("새 이름");
        assertThat(text("SELECT row_to_json(d)::text FROM mst.dev d WHERE mng_no='NAME1'")).isEqualTo(children);assertThat(text("SELECT row_to_json(p)::text FROM prj.prj p WHERE prj_no='NAME1'")).isEqualTo(project);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id='"+id+"' AND tbl_nm='mst.bsn_part' AND clmn_nm='bsn_part_nm' AND bfr_val='영업 파트' AND aft_val='새 이름'")).isEqualTo(1);
        String parent=text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id);long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        service.rename(id,"새 이름");service.enable(id);assertThat(text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id)).isEqualTo(parent);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void renamePreservesBusinessPlanAndItsReferenceWhileJoinReadsNewName()throws Exception {
        String id=create("영업 파트");
        execute("INSERT INTO fin.biz_plan(plan_yr,plan_ym,bsn_part_id) VALUES('2026','2026-11',"+id+")");
        String before=text("SELECT row_to_json(p)::text FROM fin.biz_plan p WHERE bsn_part_id="+id);
        var standards=parts.get(id).standards();
        service.rename(id,"새 이름");
        assertThat(text("SELECT p.bsn_part_nm FROM fin.biz_plan b JOIN mst.bsn_part p USING(bsn_part_id) WHERE b.bsn_part_id="+id)).isEqualTo("새 이름");
        assertThat(text("SELECT row_to_json(p)::text FROM fin.biz_plan p WHERE bsn_part_id="+id)).isEqualTo(before);
        assertThat(parts.get(id).standards()).isEqualTo(standards);
    }
    @Test void renameAuditFailureRollsBackParentNameAndTimestamp()throws Exception {
        String id=create("영업 파트");
        String before=text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id);
        long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_name_change CHECK (clmn_nm IS DISTINCT FROM 'bsn_part_nm') NOT VALID");
        assertThatThrownBy(()->service.rename(id,"새 이름")).isInstanceOf(RuntimeException.class);
        assertThat(text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id)).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void duplicateStoppedNameDoesNotChangeParentOrHistory()throws Exception {
        String id=create("영업 파트"),stopped=create("중지 파트");service.disable(stopped);
        String before=text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id);
        long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.rename(id,"중지 파트")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("DUPLICATE_PART_NAME"));
        assertThat(text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id)).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void headquartersRenameKeepsEntireRowAndHistory()throws Exception {
        String id=service.options().stream().filter(SalesPartOption::hq).findFirst().orElseThrow().id();
        String before=text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id);
        long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.rename(id,"새 이름")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("HQ_PART_LOCKED"));
        assertThat(text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id)).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void headquartersDisableKeepsEntireRowAndHistory()throws Exception {
        String id=service.options().stream().filter(SalesPartOption::hq).findFirst().orElseThrow().id();
        String before=text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id);
        long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.disable(id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("HQ_PART_LOCKED"));
        assertThat(text("SELECT row_to_json(p)::text FROM mst.bsn_part p WHERE bsn_part_id="+id)).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void disableBlockPeopleThreeDoesNotChangeAnyRows()throws Exception {
        String id=create("인력 파트");execute("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,bsn_part_id) SELECT 'PERSON'||g,'인력'||g,'FT',"+id+" FROM generate_series(1,3) g");
        long history=scalar("SELECT count(*) FROM sys.data_chg_hist");assertThatThrownBy(()->service.disable(id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PART_IN_USE"));
        assertThat(parts.get(id).use_yn()).isEqualTo("Y");assertThat(scalar("SELECT count(*) FROM mst.dev WHERE bsn_part_id="+id)).isEqualTo(3);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);
    }
    @Test void disableBlockProjectAndResignationDateBoundary()throws Exception {
        String id=create("진행 파트");execute("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,bsn_part_id,rsgn_dt) VALUES('RESIGNED','오늘 퇴사','FT',"+id+",'2026-10-22')");
        execute("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES('RUN1','진행','TK','RUN',"+id+")");
        assertThat(parts.get(id).peopleCount()).isZero();assertThatThrownBy(()->service.disable(id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PART_IN_USE"));
        execute("UPDATE prj.prj SET prj_stts_cd='END' WHERE prj_no='RUN1'");String dev=text("SELECT row_to_json(d)::text FROM mst.dev d WHERE mng_no='RESIGNED'"),project=text("SELECT row_to_json(p)::text FROM prj.prj p WHERE prj_no='RUN1'");
        service.disable(id);service.disable(id);service.enable(id);
        assertThat(text("SELECT row_to_json(d)::text FROM mst.dev d WHERE mng_no='RESIGNED'")).isEqualTo(dev);assertThat(text("SELECT row_to_json(p)::text FROM prj.prj p WHERE prj_no='RUN1'")).isEqualTo(project);
    }
    @Test void standardNovemberClosesJanuaryAndKeepsOctoberFortyPercent()throws Exception {
        String id=create("영업 파트");january(id);var result=service.standard(id,standard("2026-11","45"));
        assertThat(result.currentStandard().prft_dstrb_rt()).isEqualByComparingTo("40");assertThat(result.currentStandard().aply_end_ym()).isEqualTo("2026-10");assertThat(result.scheduledChanges().get(0).aply_end_ym()).isNull();
        assertThat(result.scheduledChanges().get(0).prft_dstrb_rt()).isEqualByComparingTo("45");assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist WHERE bsn_part_id="+id+" AND ldr_user_id IS NOT NULL")).isZero();
        execute("UPDATE cmn.oprt_std SET num_val=1 WHERE oprt_std_id IN ('PART_PROFIT_RATE','PART_SALES_FEE')");assertThat(parts.get(id).standards()).isEqualTo(result.standards());
    }
    @Test void futureReplacementAuditsBothDeletedRowsAndKeepsEarlier()throws Exception {
        String id=create("교체 파트");january(id);service.standard(id,standard("2026-11","42"));service.standard(id,standard("2026-12","50"));
        var previous=parts.get(id).standards();var result=service.standard(id,standard("2026-11","45"));
        assertThat(result.standards()).hasSize(2);assertThat(result.currentStandard().part_std_hist_id()).isEqualTo(previous.get(2).part_std_hist_id());
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.bsn_part_std_hist' AND data_chg_ty_cd='DELETE' AND bfr_val IS NOT NULL AND aft_val IS NULL")).isEqualTo(2);
        assertThat(result.standards().get(0).aply_bgn_ym()).isEqualTo("2026-11");assertThat(result.standards().get(0).aply_end_ym()).isNull();
    }
    @Test void decemberChangeClosesNovemberNotDeletesIt()throws Exception {String id=create("연속 파트");service.standard(id,standard("2026-11","45"));String november=parts.get(id).standards().get(0).part_std_hist_id();var result=service.standard(id,standard("2026-12","50"));assertThat(result.standards().get(1).part_std_hist_id()).isEqualTo(november);assertThat(result.standards().get(1).aply_end_ym()).isEqualTo("2026-11");assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='DELETE'")).isZero();}
    @Test void sameStandardDoesNotChangeStandardsOrAuditHistory()throws Exception {
        String id=create("영업 파트");january(id);var before=parts.get(id);long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.standard(id,standard("2026-11","40"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SAME_STANDARD"));
        assertThat(parts.get(id).standards()).isEqualTo(before.standards());assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void closedMonthDoesNotChangeStandardsOrAuditHistory()throws Exception {
        String id=create("영업 파트");january(id);var before=parts.get(id);long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.standard(id,standard("2026-09","45"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(parts.get(id).standards()).isEqualTo(before.standards());assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void headquartersStandardDoesNotChangeStandardsOrAuditHistory()throws Exception {
        String hq=service.options().stream().filter(SalesPartOption::hq).findFirst().orElseThrow().id();
        var before=parts.get(hq);long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.standard(hq,standard("2026-11","45"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("HQ_PART_LOCKED"));
        assertThat(parts.get(hq)).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void headquartersRenameDoesNotChangeStandardsOrAuditHistory()throws Exception {
        String hq=service.options().stream().filter(SalesPartOption::hq).findFirst().orElseThrow().id();
        var before=parts.get(hq);long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.rename(hq,"다른 본사")).isInstanceOf(ApiException.class);
        assertThat(parts.get(hq)).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void headquartersDisableDoesNotChangeStandardsOrAuditHistory()throws Exception {
        String hq=service.options().stream().filter(SalesPartOption::hq).findFirst().orElseThrow().id();
        var before=parts.get(hq);long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.disable(hq)).isInstanceOf(ApiException.class);
        assertThat(parts.get(hq)).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void startedReplacementDoesNotChangeStandardsOrAuditHistory()throws Exception {
        String id=create("영업 파트");january(id);var before=parts.get(id);long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        execute("DELETE FROM fin.mth_cls");assertThatThrownBy(()->service.standard(id,standard("2026-01","45"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.field()).isEqualTo("aply_bgn_ym"));assertThat(parts.get(id).standards()).isEqualTo(before.standards());assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void sameScheduledStandardKeepsAllFutureRowsAndAudits()throws Exception {
        String id=create("예정 파트");service.standard(id,standard("2026-11","45"));service.standard(id,standard("2026-12","50"));
        var before=parts.get(id);long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.standard(id,standard("2026-11","45.00"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SAME_STANDARD"));
        assertThat(parts.get(id)).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void auditFailureRollsBackDeleteCloseAndInsertTogether()throws Exception {
        String id=create("원자 파트");service.standard(id,standard("2026-11","42"));var before=parts.get(id).standards();long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");
        execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_new_standard CHECK (NOT (tbl_nm='mst.bsn_part_std_hist' AND data_chg_ty_cd='INSERT')) NOT VALID");
        assertThatThrownBy(()->service.standard(id,standard("2026-11","45"))).isInstanceOf(RuntimeException.class);
        assertThat(parts.get(id).standards()).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void foreignKeyRefusalRollsBackAndTranslates()throws Exception {
        String id=create("참조 파트");service.standard(id,standard("2026-11","42"));var before=parts.get(id).standards();
        execute("CREATE TABLE public.standard_reference(id bigint REFERENCES mst.bsn_part_std_hist(part_std_hist_id))");execute("INSERT INTO public.standard_reference VALUES("+before.get(0).part_std_hist_id()+")");
        long audits=scalar("SELECT count(*) FROM sys.data_chg_hist");assertThatThrownBy(()->service.standard(id,standard("2026-11","45"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.field()).isEqualTo("aply_bgn_ym"));assertThat(parts.get(id).standards()).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
    }
    @Test void concurrentSamePartStandardsSerializeWithoutOverlaps()throws Exception {
        String id=create("동시 파트");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {var first=pool.submit(()->{start.await();return service.standard(id,standard("2026-11","45"));});var second=pool.submit(()->{start.await();return service.standard(id,standard("2026-12","50"));});start.countDown();first.get(15,TimeUnit.SECONDS);second.get(15,TimeUnit.SECONDS);
            assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist a JOIN mst.bsn_part_std_hist b ON a.bsn_part_id=b.bsn_part_id AND a.part_std_hist_id<b.part_std_hist_id AND a.aply_bgn_ym<=COALESCE(b.aply_end_ym,'9999-12') AND b.aply_bgn_ym<=COALESCE(a.aply_end_ym,'9999-12') WHERE a.bsn_part_id="+id)).isZero();
        } finally {pool.shutdownNow();}
    }
    @Test void exportThreePagesUsesAllRowsAndOrderedColumnsAndOnlyOneAccess()throws Exception {
        for(int i=1;i<=100;i++)create("파일 파트 "+i);String stopped=create("중지 파일 파트");service.disable(stopped);
        var page=parts.list(new PageRequest(2,50,null));assertThat(page.items()).hasSize(50);assertThat(page.total()).isEqualTo(102);
        long history=scalar("SELECT count(*) FROM sys.data_chg_hist"),access=scalar("SELECT count(*) FROM sys.acs_hist");
        var file=exports.export(List.of("currentStandard.bsn_fee_amt","bsn_part_nm","use_yn"),null);
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(file.bytes()))) {var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()-3).isEqualTo(page.total());assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("영업비(1인·월)");assertThat(sheet.getRow(5).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);assertThat(sheet.getRow(105).getCell(2).getStringCellValue()).isEqualTo("N");}
        assertThat(file.filename()).isEqualTo("영업파트_20261022.xlsx");assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access+1);assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-15' AND user_id IS NULL AND obj_desc='영업파트_20261022.xlsx' AND dtl_desc::jsonb->'columns'->>0='currentStandard.bsn_fee_amt'")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);
    }
    @Test void exportSelectedOnlyAndUnknownIdCreatesNoAccess()throws Exception {
        String first=create("첫 파일"),second=create("둘째 파일");var file=exports.export(List.of("bsn_part_nm"),List.of(second,first,first));try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(file.bytes()))){var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(5);assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("첫 파일");}
        long access=scalar("SELECT count(*) FROM sys.acs_hist");assertThatThrownBy(()->exports.export(List.of("bsn_part_nm"),List.of(first,"9223372036854775807"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.field()).isEqualTo("ids"));assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access);
    }
}
