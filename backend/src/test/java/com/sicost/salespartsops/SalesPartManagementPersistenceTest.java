package com.sicost.salespartsops;

import static com.sicost.salespartsops.SalesPartModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class SalesPartManagementPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    SalesPartManagementService service;
    SalesPartExcelService excel;
    SalesPartManagementMapper mapper;
    long partId;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){s.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/salespartsops/SalesPartManagementMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(SalesPartManagementMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,SalesPartManagementService.class,SalesPartExcelService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(SalesPartManagementMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper());context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();
        service=context.getBean(SalesPartManagementService.class);excel=context.getBean(SalesPartExcelService.class);
        partId=scalar("INSERT INTO mst.bsn_part(bsn_part_nm,sort_seq) VALUES('시험 파트',10) RETURNING bsn_part_id");
        execute("INSERT INTO mst.bsn_part_std_hist(bsn_part_id,aply_bgn_ym,ldr_nm,prft_dstrb_rt,bsn_fee_amt) VALUES("+partId+",'2026-01','파트장',40,1500000)");
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){s.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    String text(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getString(1);}}
    void execute(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String input(String month,int rate) {return "{\"aply_bgn_ym\":\""+month+"\",\"ldr_nm\":\"파트장\",\"prft_dstrb_rt\":"+rate+",\"bsn_fee_amt\":1500000}";}
    Read query(){return new Read(partId,LocalDate.of(2026,10,22),"2026-10");}
    String id(){return Long.toString(partId);}
    @Test void standardReplacementPreservesPastAndWritesDeleteAndInsertSummaries()throws Exception {
        service.saveStandard(id(),input("2026-11",45));
        assertThat(mapper.standards(query()).get(0).aply_end_ym()).isEqualTo("2026-10");
        service.saveStandard(id(),input("2026-11",50));var rows=mapper.standards(query());assertThat(rows).hasSize(2);assertThat(rows.get(0).prft_dstrb_rt()).isEqualByComparingTo("40");assertThat(rows.get(1).prft_dstrb_rt()).isEqualByComparingTo("50");assertThat(rows.get(1).aply_end_ym()).isNull();
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.bsn_part_std_hist' AND data_chg_ty_cd='DELETE' AND (bfr_val::jsonb->>'prft_dstrb_rt')::numeric=45 AND chg_user_id IS NULL")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.bsn_part_std_hist' AND data_chg_ty_cd='INSERT' AND (aft_val::jsonb->>'prft_dstrb_rt')::numeric=50")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist WHERE bsn_part_id="+partId+" AND ldr_user_id IS NOT NULL")).isZero();
    }
    @Test void laterFutureChangeKeepsEarlierFutureRow()throws Exception {
        service.saveStandard(id(),input("2026-11",45));String november=mapper.standards(query()).get(1).part_std_hist_id();service.saveStandard(id(),input("2026-12",50));
        assertThat(mapper.standards(query())).hasSize(3);assertThat(mapper.standards(query()).get(1).part_std_hist_id()).isEqualTo(november);assertThat(mapper.standards(query()).get(1).aply_end_ym()).isEqualTo("2026-11");
    }
    @Test void auditFailureRollsBackDeletionClosingAndInsertion()throws Exception {
        service.saveStandard(id(),input("2026-11",45));var before=mapper.standards(query());long events=scalar("SELECT count(*) FROM sys.data_chg_hist");
        execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK (false) NOT VALID");
        assertThatThrownBy(()->service.saveStandard(id(),input("2026-11",50))).isInstanceOf(RuntimeException.class);
        assertThat(mapper.standards(query())).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(events);
    }
    @Test void renameOnlyTouchesPartAndJoinedNamesChangeWithoutTouchingConnections()throws Exception {
        execute("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,bsn_part_id) VALUES('TEST-DEV','시험 인력','FT',"+partId+")");
        execute("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES('TEST-PRJ','종료 프로젝트','TK','END',"+partId+")");
        String developer=text("SELECT row_to_json(d)::text FROM mst.dev d WHERE bsn_part_id="+partId),project=text("SELECT row_to_json(p)::text FROM prj.prj p WHERE bsn_part_id="+partId);
        service.rename(id(),"{\"bsn_part_nm\":\"새 이름\"}");
        assertThat(text("SELECT p.bsn_part_nm FROM mst.dev d JOIN mst.bsn_part p USING(bsn_part_id) WHERE d.bsn_part_id="+partId)).isEqualTo("새 이름");
        assertThat(text("SELECT row_to_json(d)::text FROM mst.dev d WHERE bsn_part_id="+partId)).isEqualTo(developer);assertThat(text("SELECT row_to_json(p)::text FROM prj.prj p WHERE bsn_part_id="+partId)).isEqualTo(project);
        service.rename(id(),"{\"bsn_part_nm\":\"새 이름\"}");assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.bsn_part' AND obj_rec_id='"+id()+"' AND clmn_nm='bsn_part_nm'")).isEqualTo(1);
    }
    @Test void countsExcludeRetiredTodayAndEndedProjectsWhileEachActiveScopeBlocks()throws Exception {
        execute("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,bsn_part_id,rsgn_dt) SELECT 'TEST-'||n,'인력','FT',"+partId+",CASE WHEN n=4 THEN DATE '2026-10-22' ELSE NULL END FROM generate_series(1,4) n");
        assertThat(mapper.part(query()).peopleCount()).isEqualTo(3);assertThatThrownBy(()->service.disable(id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PART_IN_USE"));
        execute("UPDATE mst.dev SET use_yn='N' WHERE bsn_part_id="+partId);
        execute("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES('TEST-PRJ','진행 프로젝트','TK','RUN',"+partId+")");
        assertThatThrownBy(()->service.disable(id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PART_IN_USE"));assertThat(mapper.part(query()).use_yn()).isEqualTo("Y");
    }
    @Test void disableEnableAreIdempotentPreserveLinksAndOptionsReadHasNoSideEffects()throws Exception {
        execute("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,use_yn,bsn_part_id) VALUES('TEST-DEV','퇴사 인력','FT','N',"+partId+")");
        execute("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES('TEST-PRJ','종료 프로젝트','TK','END',"+partId+")");
        String developer=text("SELECT row_to_json(d)::text FROM mst.dev d WHERE bsn_part_id="+partId),project=text("SELECT row_to_json(p)::text FROM prj.prj p WHERE bsn_part_id="+partId);var before=mapper.standards(query());
        service.disable(id());service.disable(id());assertThat(service.options()).noneMatch(o->o.id().equals(id()));service.enable(id());service.enable(id());assertThat(service.options()).anyMatch(o->o.id().equals(id()));
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.bsn_part' AND obj_rec_id='"+id()+"'")).isEqualTo(2);assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isZero();assertThat(mapper.standards(query())).isEqualTo(before);
        assertThat(text("SELECT row_to_json(d)::text FROM mst.dev d WHERE bsn_part_id="+partId)).isEqualTo(developer);assertThat(text("SELECT row_to_json(p)::text FROM prj.prj p WHERE bsn_part_id="+partId)).isEqualTo(project);
    }
    @Test void exportSelectedAndWholeListsAreRealXlsxAndAuditOnlyOncePerDownload()throws Exception {
        execute("INSERT INTO mst.bsn_part(bsn_part_nm,sort_seq,use_yn) SELECT '파일 시험 '||n,n+10,CASE WHEN n=42 THEN 'N' ELSE 'Y' END FROM generate_series(1,42) n");
        var file=excel.export(List.of("bsn_part_nm","currentStandard.bsn_fee_amt"),null,null);
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(file.bytes()))) {assertThat(book.getSheetAt(0).getLastRowNum()-3).isEqualTo(scalar("SELECT count(*) FROM mst.bsn_part"));}
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-15' AND obj_desc='영업파트_20261022.xlsx' AND user_id IS NULL AND dtl_desc::jsonb->>'referenceMonth'='2026-10'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isZero();
        long other=scalar("SELECT max(bsn_part_id) FROM mst.bsn_part");var selected=excel.export(List.of("bsn_part_nm"),List.of(id(),Long.toString(other)),null);
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(selected.bytes()))) {assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(5);}
    }
}
