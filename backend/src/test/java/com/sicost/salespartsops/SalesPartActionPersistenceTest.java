package com.sicost.salespartsops;

import static com.sicost.salespartsops.SalesPartActionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class SalesPartActionPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database,id;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    SalesPartActionService service;
    SalesPartActionMapper mapper;
    JdbcTemplate sql;
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/salespartsops/SalesPartActionMapper.xml"));factory.afterPropertiesSet();
        var template=new SqlSessionTemplate(factory.getObject());mapper=template.getMapper(SalesPartActionMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,SalesPartActionService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(SalesPartActionMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->json);context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(SalesPartActionService.class);
        sql=new JdbcTemplate(source);id=sql.queryForObject("INSERT INTO mst.bsn_part(bsn_part_nm,sort_seq) VALUES('검증 파트',1) RETURNING bsn_part_id",String.class);
        sql.update("INSERT INTO mst.bsn_part_std_hist(bsn_part_id,aply_bgn_ym,ldr_nm,prft_dstrb_rt,bsn_fee_amt) VALUES(?,'2026-01','파트장',40,1500000)",Long.valueOf(id));
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    Read read() {return new Read(id,"2026-10",LocalDate.of(2026,10,22));}
    long count(String table) {return sql.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    com.fasterxml.jackson.databind.JsonNode next(String month,int rate)throws Exception {return json.readTree("{\"aply_bgn_ym\":\""+month+"\",\"ldr_nm\":\"새 파트장\",\"prft_dstrb_rt\":"+rate+",\"bsn_fee_amt\":1500000}");}
    @Test void optionsSortIncludesHqExcludesDisabledAndDoesNotWrite() {
        sql.update("INSERT INTO mst.bsn_part(bsn_part_nm,sort_seq,use_yn) VALUES('중지 파트',0,'N')");
        long parts=count("mst.bsn_part"),hist=count("sys.data_chg_hist"),access=count("sys.acs_hist");
        assertThat(service.options()).extracting(Option::id).contains(id,"1");assertThat(service.options()).noneMatch(o->o.name().equals("중지 파트"));assertThat(service.options().get(0).id()).isEqualTo(id);assertThat(service.options()).anyMatch(Option::hq);
        assertThat(count("mst.bsn_part")).isEqualTo(parts);assertThat(count("sys.data_chg_hist")).isEqualTo(hist);assertThat(count("sys.acs_hist")).isEqualTo(access);
    }
    @Test void disableEnablePreserveHistoryAndRemoveRestoreOptionWithNoOpAudits() {
        var before=mapper.standards(read());service.disable(id);assertThat(mapper.part(id).use_yn()).isEqualTo("N");assertThat(service.options()).noneMatch(o->o.id().equals(id));
        service.disable(id);assertThat(sql.queryForObject("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id=? AND data_chg_ty_cd='DISABLE'",Long.class,id)).isEqualTo(1);
        service.enable(id);service.enable(id);assertThat(service.options()).anyMatch(o->o.id().equals(id));assertThat(mapper.standards(read())).isEqualTo(before);
        assertThat(sql.queryForObject("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id=? AND data_chg_ty_cd='UPDATE' AND clmn_nm='use_yn' AND bfr_val='N' AND aft_val='Y'",Long.class,id)).isEqualTo(1);
    }
    void addPeople(int n) {
        for(int i=0;i<n;i++)sql.update("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,bsn_part_id) VALUES(?,?,'EMP',?)","검증"+i,"인력"+i,Long.valueOf(id));
    }
    @Test void threeAffiliatedPeoplePreventDisableAndRemainUnchanged() {
        addPeople(3);var before=sql.queryForList("SELECT * FROM mst.dev");
        assertThatThrownBy(()->service.disable(id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PART_IN_USE"));
        assertThat(mapper.part(id).use_yn()).isEqualTo("Y");assertThat(sql.queryForList("SELECT * FROM mst.dev")).isEqualTo(before);assertThat(count("sys.data_chg_hist")).isZero();
    }
    @Test void ongoingProjectWithoutAssignmentsPreventsDisable() {
        sql.update("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES('검증','진행','TK','RUN',?)",Long.valueOf(id));
        var before=sql.queryForList("SELECT * FROM prj.prj");assertThatThrownBy(()->service.disable(id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PART_IN_USE"));assertThat(sql.queryForList("SELECT * FROM prj.prj")).isEqualTo(before);
    }
    @Test void resignationTodayDoesNotCountAndStateChangesPreserveDeveloperRows() {
        addPeople(1);sql.update("UPDATE mst.dev SET rsgn_dt='2026-10-22' WHERE bsn_part_id=?",Long.valueOf(id));var before=sql.queryForList("SELECT * FROM mst.dev");
        service.disable(id);service.enable(id);assertThat(sql.queryForList("SELECT * FROM mst.dev")).isEqualTo(before);
    }
    @Test void renameReadsNewNameViaSameDeveloperForeignKeyAndNoOpPreservesTimestamp()throws Exception {
        addPeople(1);var before=sql.queryForList("SELECT * FROM mst.dev");service.rename(id,json.readTree("{\"bsn_part_nm\":\"새 이름\"}"));
        assertThat(sql.queryForObject("SELECT p.bsn_part_nm FROM mst.dev d JOIN mst.bsn_part p ON p.bsn_part_id=d.bsn_part_id WHERE d.bsn_part_id=?",String.class,Long.valueOf(id))).isEqualTo("새 이름");assertThat(sql.queryForList("SELECT * FROM mst.dev")).isEqualTo(before);
        var timestamp=sql.queryForObject("SELECT updated_at FROM mst.bsn_part WHERE bsn_part_id=?",Timestamp.class,Long.valueOf(id));long history=count("sys.data_chg_hist");
        service.rename(id,json.readTree("{\"bsn_part_nm\":\"새 이름\"}"));assertThat(count("sys.data_chg_hist")).isEqualTo(history);assertThat(sql.queryForObject("SELECT updated_at FROM mst.bsn_part WHERE bsn_part_id=?",Timestamp.class,Long.valueOf(id))).isEqualTo(timestamp);
    }
    @Test void novemberSnapshotPreservesOctoberAndAddsCloseAndInsertAudit()throws Exception {
        var detail=service.saveStandard(id,next("2026-11",45));assertThat(detail.currentStandard().prft_dstrb_rt()).isEqualByComparingTo("40");assertThat(detail.currentStandard().aply_end_ym()).isEqualTo("2026-10");assertThat(detail.scheduledChanges()).hasSize(1);assertThat(detail.scheduledChanges().get(0).aply_end_ym()).isNull();
        assertThat(sql.queryForObject("SELECT count(*) FROM mst.bsn_part_std_hist WHERE bsn_part_id=? AND ldr_user_id IS NOT NULL",Long.class,Long.valueOf(id))).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE' AND clmn_nm='aply_end_ym' AND aft_val='2026-10'",Long.class)).isEqualTo(1);assertThat(count("sys.data_chg_hist")).isEqualTo(2);
    }
    @Test void replacingNovemberDeletesOnlyFutureWithBeforeAndAfterSummaries()throws Exception {
        service.saveStandard(id,next("2026-11",45));String original=mapper.standards(read()).get(0).part_std_hist_id();service.saveStandard(id,next("2026-11",46));
        assertThat(mapper.standards(read())).hasSize(2);assertThat(mapper.standards(read()).get(0).part_std_hist_id()).isNotEqualTo(original);
        assertThat(sql.queryForObject("SELECT bfr_val FROM sys.data_chg_hist WHERE data_chg_ty_cd='DELETE' AND obj_rec_id=?",String.class,original)).contains("2026-11","45");
    }
    @Test void decemberDoesNotDeleteNovember()throws Exception {
        service.saveStandard(id,next("2026-11",45));service.saveStandard(id,next("2026-12",46));var rows=mapper.standards(read());assertThat(rows).hasSize(3);assertThat(rows.get(1).aply_bgn_ym()).isEqualTo("2026-11");assertThat(rows.get(1).aply_end_ym()).isEqualTo("2026-11");assertThat(sql.queryForObject("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='DELETE'",Long.class)).isZero();
    }
    @Test void historyFailureRollsBackDeletedFutureClosedPeriodAndNewSnapshot()throws Exception {
        service.saveStandard(id,next("2026-11",45));var rows=mapper.standards(read());long history=count("sys.data_chg_hist");
        sql.execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_new_history CHECK (data_chg_ty_cd != 'INSERT') NOT VALID");
        assertThatThrownBy(()->service.saveStandard(id,next("2026-11",46))).isInstanceOf(RuntimeException.class);assertThat(mapper.standards(read())).isEqualTo(rows);assertThat(count("sys.data_chg_hist")).isEqualTo(history);
    }
    @Test void renameAuditFailureRollsBackPart()throws Exception {sql.execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_new_history CHECK (false) NOT VALID");assertThatThrownBy(()->service.rename(id,json.readTree("{\"bsn_part_nm\":\"새 이름\"}"))).isInstanceOf(RuntimeException.class);assertThat(mapper.part(id).bsn_part_nm()).isEqualTo("검증 파트");}
    @Test void exportIncludesStoppedRowsAndCommitsExactlyOneDownloadOnly()throws Exception {
        service.disable(id);long history=count("sys.data_chg_hist");var standards=mapper.standards(read());File file=service.export(List.of("bsn_part_nm","bsn_fee_amt"),null);
        assertThat(file.bytes()).startsWith((byte)'P',(byte)'K');assertThat(sql.queryForObject("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-15' AND user_id IS NULL AND obj_desc=?",Long.class,file.filename())).isEqualTo(1);String details=sql.queryForObject("SELECT dtl_desc FROM sys.acs_hist WHERE obj_desc=?",String.class,file.filename());assertThat(json.readTree(details).get("rowCount").asLong()).isEqualTo(count("mst.bsn_part"));assertThat(count("sys.data_chg_hist")).isEqualTo(history);assertThat(mapper.standards(read())).isEqualTo(standards);
    }
}
