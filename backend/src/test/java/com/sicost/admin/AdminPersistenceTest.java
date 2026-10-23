package com.sicost.admin;

import static com.sicost.admin.AdminModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import com.sicost.common.api.PageRequest;
import com.sicost.common.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class AdminPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    AdminService service;
    AdminMapper mapper;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/admin/AdminMapper.xml"));factory.afterPropertiesSet();
        var template=new SqlSessionTemplate(factory.getObject());mapper=template.getMapper(AdminMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,AdminService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(AdminMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(AdminService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    long scalar(String sql)throws Exception {try(var connection=source.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    CodeRow changed(CodeRow r,String name,String use,String attr) {return new CodeRow(r.dtl_cd(),name,r.sort_seq(),use,attr,r.attr2_val(),r.attr3_val(),r.aply_bgn_dt());}
    @Test void allManagementMappersExecuteAndHistoryFilterTotalMatches()throws Exception {
        assertThat(mapper.groups(AdminQuery.of(new PageRequest(1,50,null),"groups",null,null,null))).hasSize(36);
        assertThat(mapper.allCodes("JOB")).hasSize(12);assertThat(mapper.group("JOB").attr1_nm()).isEqualTo("기본 단가(원)");
        var groupInput=new CodeGroupCreate("NEW_GROUP","새 그룹",null,null,null);service.createGroup(groupInput);
        assertThat(mapper.group("NEW_GROUP").cd_grp_se_cd()).isEqualTo("BIZ");assertThat(mapper.group("NEW_GROUP").sort_seq()).isEqualTo(scalar("SELECT max(sort_seq) FROM cmn.cd_grp"));
        var q=AdminQuery.of(new PageRequest(1,50,null),"history","NEW_GROUP",null,null);assertThat(mapper.history(q)).hasSize(1);assertThat(mapper.historyCount(q)).isEqualTo(1);assertThat(mapper.history(q).get(0).chg_user_id()).isNull();
        service.list("params",new PageRequest(1,50,null),null,null,DataScope.ALL);service.list("maps",new PageRequest(1,50,null),null,null,DataScope.ALL);
    }
    @Test void codeSaveDisableEnableAndLastSaveWinArePersistent()throws Exception {
        var original=mapper.allCodes("JOB");var rows=new ArrayList<>(original);var index=java.util.stream.IntStream.range(0,rows.size()).filter(i->rows.get(i).dtl_cd().equals("OPS")).findFirst().orElseThrow();var ops=rows.get(index);
        rows.set(index,changed(ops,"먼저","N",ops.attr1_val()));service.saveCodes("JOB",rows);assertThat(mapper.allCodes("JOB").stream().filter(r->r.dtl_cd().equals("OPS")).findFirst().orElseThrow().use_yn()).isEqualTo("N");
        rows.set(index,changed(ops,"나중","Y",ops.attr1_val()));service.saveCodes("JOB",rows);
        assertThat(mapper.allCodes("JOB").stream().filter(r->r.dtl_cd().equals("OPS")).findFirst().orElseThrow().cd_nm()).isEqualTo("나중");
        assertThat(scalar("SELECT count(*) FROM cmn.cd_hist WHERE dtl_cd='OPS' AND cd_chg_ty_cd IN ('DISABLE','ENABLE')")).isEqualTo(2);
        rows.add(new CodeRow("TEST1","테스트",rows.stream().mapToInt(CodeRow::sort_seq).max().orElseThrow()+1,"Y",null,null,null,null));service.saveCodes("JOB",rows);
        assertThat(scalar("SELECT count(*) FROM cmn.cd_dtl WHERE cd_grp_id='JOB' AND dtl_cd='TEST1' AND created_by IS NULL AND use_yn='Y'")).isEqualTo(1);
    }
    @Test void missingOrDuplicateCodeRollsBackAll()throws Exception {
        var rows=mapper.allCodes("JOB");long before=scalar("SELECT count(*) FROM cmn.cd_hist");
        assertThatThrownBy(()->service.saveCodes("JOB",rows.subList(1,rows.size()))).isInstanceOf(com.sicost.common.api.ApiException.class);
        var duplicate=new ArrayList<>(rows);duplicate.add(rows.get(0));assertThatThrownBy(()->service.saveCodes("JOB",duplicate)).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThat(mapper.allCodes("JOB")).isEqualTo(rows);assertThat(scalar("SELECT count(*) FROM cmn.cd_hist")).isEqualTo(before);
    }
    @Test void auditFailureRollsBackAlreadyWrittenCode()throws Exception {
        var original=mapper.allCodes("POSITION");var changed=new ArrayList<>(original);var r=changed.get(0);changed.set(0,changed(r,"새 이름",r.use_yn(),r.attr1_val()));
        try(var c=source.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE cmn.cd_hist ADD CONSTRAINT reject_history CHECK (false) NOT VALID");}
        assertThatThrownBy(()->service.saveCodes("POSITION",changed)).isInstanceOf(RuntimeException.class);assertThat(mapper.allCodes("POSITION")).isEqualTo(original);
    }
    @Test void mappingCreatesDistinctTargetsAndPatchPreservesNamesAndAppendOnlyHistory()throws Exception {
        var first=new ExcelMapCreate("테스트표기","JOB","BE","MAIN","메모");service.createMapping(first);
        var alternate=mapper.allCodes("JOB").stream().filter(r->!r.dtl_cd().equals("BE")&&r.use_yn().equals("Y")).findFirst().orElseThrow();service.createMapping(new ExcelMapCreate("테스트표기","JOB",alternate.dtl_cd(),"SUB",null));
        long count=mapper.mapCount();assertThatThrownBy(()->service.createMapping(first)).isInstanceOf(com.sicost.common.api.ApiException.class);assertThat(mapper.mapCount()).isEqualTo(count);
        var q=AdminQuery.of(new PageRequest(1,100,null),"maps",null,null,null);var row=mapper.maps(q).stream().filter(m->m.excel_lbl_nm().equals("테스트표기")&&m.trns_cd().equals("BE")).findFirst().orElseThrow();
        service.patchMapping(row.map_id(),new ExcelMapPatch("SUB","수정 메모","N",Set.of("map_role_cd","rmrk_desc","use_yn")));
        var saved=mapper.mapping(row.map_id());assertThat(saved.map_role_cd()).isEqualTo("SUB");assertThat(saved.rmrk_desc()).isEqualTo("수정 메모");assertThat(saved.excel_lbl_nm()).isEqualTo(row.excel_lbl_nm());assertThat(saved.trns_cd()).isEqualTo(row.trns_cd());assertThat(saved.use_yn()).isEqualTo("N");
        assertThat(scalar("SELECT count(*) FROM cmn.cd_hist WHERE item_nm='map_id="+row.map_id()+":ADD' AND cd_chg_ty_cd='ADD'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM cmn.cd_hist WHERE item_nm='map_id="+row.map_id()+":rmrk_desc' AND bfr_val='메모' AND aft_val='수정 메모'")).isEqualTo(1);
    }
    @Test void parameterCompositeUpsertPreservesPastAndFutureAndCopiesMetadata()throws Exception {
        var past=mapper.source("VAT_RATE","2026-10");service.saveParam("VAT_RATE","2026-11",new ParamValueSave(BigDecimal.valueOf(11),null));service.saveParam("VAT_RATE","2026-11",new ParamValueSave(BigDecimal.valueOf(12),null));
        assertThat(mapper.source("VAT_RATE","2026-10")).isEqualTo(past);assertThat(mapper.source("VAT_RATE","2026-11").num_val()).isEqualByComparingTo("12");assertThat(mapper.source("VAT_RATE","2026-11").oprt_std_nm()).isEqualTo(past.oprt_std_nm());assertThat(scalar("SELECT count(*) FROM cmn.oprt_std WHERE oprt_std_id='VAT_RATE' AND aply_bgn_ym='2026-11'")).isEqualTo(1);
        var versions=mapper.versions(AdminQuery.of(new PageRequest(1,100,null),"versions",null,"VAT_RATE",null));assertThat(versions.get(0).aply_bgn_ym()).isEqualTo("2026-11");
        var current=mapper.params(AdminQuery.of(new PageRequest(1,100,null),"params",null,null,"2026-10"));assertThat(current.stream().filter(r->r.oprt_std_id().equals("VAT_RATE")).findFirst().orElseThrow().num_val()).isEqualByComparingTo("10");
    }
    @Test void closedAndInvalidParameterLeaveValuesAndHistoryUnchanged()throws Exception {
        var before=mapper.version("VAT_RATE","2000-01");long events=scalar("SELECT count(*) FROM cmn.cd_hist");
        assertThatThrownBy(()->service.saveParam("VAT_RATE","2026-09",new ParamValueSave(BigDecimal.valueOf(11),null))).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThatThrownBy(()->service.saveParam("VAT_RATE","2026-11",new ParamValueSave(BigDecimal.valueOf(101),null))).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThat(mapper.version("VAT_RATE","2000-01")).isEqualTo(before);assertThat(mapper.version("VAT_RATE","2026-11")).isNull();assertThat(scalar("SELECT count(*) FROM cmn.cd_hist")).isEqualTo(events);
    }
}
