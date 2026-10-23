package com.sicost.salesparts;

import static com.sicost.salesparts.SalesPartModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
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

class SalesPartPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    SalesPartService service;
    SalesPartMapper mapper;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/salesparts/SalesPartMapper.xml"));factory.afterPropertiesSet();
        var template=new SqlSessionTemplate(factory.getObject());mapper=template.getMapper(SalesPartMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,SalesPartService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(SalesPartMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(SalesPartService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    String snapshot(String table,String order)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT coalesce(json_agg(t)::text,'[]') FROM (SELECT * FROM "+table+" ORDER BY "+order+") t")){r.next();return r.getString(1);}}
    SalesPartCreate input(String name,String month) {return new SalesPartCreate(name,month,"홍길동",new BigDecimal("40.25"),1500000L);}
    SalesPartQuery query(String sort,String month) {return SalesPartQuery.of(new PageRequest(1,50,sort),LocalDate.parse(month+"-22"));}
    @Test void anonymousHttpCreatePersistsParentStandardAndInsertHistoriesAtomically()throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new SalesPartController(service,json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        long parts=scalar("SELECT count(*) FROM mst.bsn_part"),standards=scalar("SELECT count(*) FROM mst.bsn_part_std_hist");
        mvc.perform(post("/api/sales-parts").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input("HTTP 신규 파트","2026-11"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.hq_yn").value("N"))
            .andExpect(jsonPath("$.data.use_yn").value("Y")).andExpect(jsonPath("$.data.currentStandard").isEmpty())
            .andExpect(jsonPath("$.data.scheduledChanges[0].aply_bgn_ym").value("2026-11"));
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part")).isEqualTo(parts+1);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist")).isEqualTo(standards+1);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist h JOIN mst.bsn_part p ON p.bsn_part_id=h.bsn_part_id WHERE p.bsn_part_nm='HTTP 신규 파트' AND h.aply_end_ym IS NULL AND h.ldr_user_id IS NULL")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='INSERT' AND chg_user_id IS NULL")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.bsn_part_std_hist' AND aft_val LIKE '2026-11%'")).isEqualTo(1);
        mvc.perform(post("/api/sales-parts").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input("HTTP 신규 파트","2026-11"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PART_NAME"));
        mvc.perform(get("/api/sales-parts").param("size","0"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("size"));
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part")).isEqualTo(parts+1);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist")).isEqualTo(standards+1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(2);
    }
    @Test void seededHeadquartersIsReadableWithoutChangingItsNullLeaderOrZeroFee() {
        var hq=service.listSalesParts(new PageRequest(1,50,null)).items().stream().filter(p->p.hq_yn().equals("Y")).findFirst().orElseThrow();
        var detail=service.getSalesPart(hq.bsn_part_id());assertThat(detail.currentStandard().ldr_nm()).isNull();assertThat(detail.currentStandard().bsn_fee_amt()).isZero();
        assertThat(detail.lastClosedMonth()).isEqualTo("2026-09");assertThat(detail.firstAllowedMonth()).isEqualTo("2026-10");
    }
    @Test void futureCreatePersistsSnapshotGeneratedKeysAndExactlyTwoInsertEvents()throws Exception {
        var result=service.createSalesPart(input("신규 파트","2026-11"));String id=result.bsn_part_id();
        assertThat(result.currentStandard()).isNull();assertThat(result.scheduledChanges()).hasSize(1);assertThat(result.sort_seq()).isEqualTo(1);assertThat(result.hq_yn()).isEqualTo("N");
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part WHERE bsn_part_id="+id)).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist WHERE bsn_part_id="+id+" AND ldr_user_id IS NULL AND aply_end_ym IS NULL")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='INSERT' AND chg_user_id IS NULL")).isEqualTo(2);
        assertThat(mapper.standards(id,"2026-10").get(0).status()).isEqualTo("예정");assertThat(mapper.standards(id,"2026-11").get(0).status()).isEqualTo("적용 중");
        String standards=snapshot("mst.bsn_part_std_hist","part_std_hist_id");
        sql("UPDATE cmn.oprt_std SET num_val=99 WHERE oprt_std_id IN ('PART_PROFIT_RATE','PART_SALES_FEE')");
        assertThat(snapshot("mst.bsn_part_std_hist","part_std_hist_id")).isEqualTo(standards);
        assertThat(service.getSalesPart(id).scheduledChanges().get(0).prft_dstrb_rt()).isEqualByComparingTo("40.25");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.bsn_part_std_hist' AND aft_val LIKE '2026-11부터 · 파트장 홍길동 · 40.25% · 1,500,000원'")).isEqualTo(1);
    }
    @Test void historyStatusAggregatesReadOnlyQueriesAndAllSortKeysExecute()throws Exception {
        var p=service.createSalesPart(input("영업 파트","2026-10"));var stopped=service.createSalesPart(input("중지 파트","2026-10"));String id=p.bsn_part_id();
        sql("UPDATE mst.bsn_part SET use_yn='N' WHERE bsn_part_id="+stopped.bsn_part_id());
        sql("UPDATE mst.bsn_part_std_hist SET aply_end_ym='2026-10' WHERE bsn_part_id="+id);
        sql("INSERT INTO mst.bsn_part_std_hist(bsn_part_id,aply_bgn_ym,aply_end_ym,ldr_nm,prft_dstrb_rt,bsn_fee_amt) VALUES ("+id+",'2026-01','2026-09','홍길동',40,1500000),("+id+",'2026-11',NULL,'홍길동',45,1500000)");
        sql("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,bsn_part_id,use_yn,rsgn_dt) VALUES ('A','소속','FT',"+id+",'Y',NULL),('B','퇴사','FT',"+id+",'Y','2026-10-21'),('C','오늘 퇴사','FT',"+id+",'Y','2026-10-22'),('D','중지','FT',"+id+",'N',NULL),('E','재직','FT',"+id+",'Y','2026-10-23')");
        sql("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES ('A','계획','MO','PLAN',"+id+"),('B','진행','MO','RUN',"+id+"),('C','보류','MO','HOLD',"+id+"),('D','완료','MO','END',"+id+")");
        sql("INSERT INTO fin.part_stlm(bsn_part_id,stlm_ym,part_std_hist_id,prft_dstrb_rt,bsn_fee_amt,stlm_stts_cd) SELECT bsn_part_id,'2026-09',part_std_hist_id,prft_dstrb_rt,bsn_fee_amt,'CONFIRM' FROM mst.bsn_part_std_hist WHERE bsn_part_id="+id+" AND aply_bgn_ym='2026-01'");
        String histories=snapshot("mst.bsn_part_std_hist","part_std_hist_id"),audit=snapshot("sys.data_chg_hist","data_chg_hist_id"),closing=snapshot("fin.mth_cls","cls_ym"),settlement=snapshot("fin.part_stlm","stlm_id");
        var list=service.listSalesParts(new PageRequest(1,50,null));assertThat(list.total()).isEqualTo(3);assertThat(list.items()).hasSize(3);
        assertThat(list.items().get(0).bsn_part_id()).isEqualTo(id);assertThat(list.items().get(1).use_yn()).isEqualTo("N");assertThat(list.items().get(2).hq_yn()).isEqualTo("Y");
        var detail=service.getSalesPart(id);assertThat(detail.peopleCount()).isEqualTo(2);assertThat(detail.projectCount()).isEqualTo(3);
        assertThat(detail.currentStandard().prft_dstrb_rt()).isEqualByComparingTo("40.25");assertThat(detail.scheduledChanges().get(0).prft_dstrb_rt()).isEqualByComparingTo("45");
        assertThat(detail.standards()).extracting(SalesPartStandard::status).containsExactly("예정","적용 중","지난 기준");
        assertThat(service.getSalesPart(stopped.bsn_part_id()).use_yn()).isEqualTo("N");
        for(String key:List.of("bsn_part_nm","ldr_nm","prft_dstrb_rt","bsn_fee_amt","aply_bgn_ym","peopleCount","projectCount","use_yn")) for(String direction:List.of("asc","desc")) assertThat(mapper.list(query(key+","+direction,"2026-10"))).hasSize(3);
        assertThat(snapshot("mst.bsn_part_std_hist","part_std_hist_id")).isEqualTo(histories);assertThat(snapshot("sys.data_chg_hist","data_chg_hist_id")).isEqualTo(audit);assertThat(snapshot("fin.mth_cls","cls_ym")).isEqualTo(closing);assertThat(snapshot("fin.part_stlm","stlm_id")).isEqualTo(settlement);
    }
    @Test void duplicateAndClosedMonthLeaveParentStandardAndAuditUnchanged()throws Exception {
        service.createSalesPart(input("신규 파트","2026-11"));
        String parts=snapshot("mst.bsn_part","bsn_part_id"),standards=snapshot("mst.bsn_part_std_hist","part_std_hist_id"),audit=snapshot("sys.data_chg_hist","data_chg_hist_id");
        assertThatThrownBy(()->service.createSalesPart(input("신규 파트","2026-11"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("DUPLICATE_PART_NAME"));
        assertThatThrownBy(()->service.createSalesPart(input("새 파트","2026-09"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot("mst.bsn_part","bsn_part_id")).isEqualTo(parts);assertThat(snapshot("mst.bsn_part_std_hist","part_std_hist_id")).isEqualTo(standards);assertThat(snapshot("sys.data_chg_hist","data_chg_hist_id")).isEqualTo(audit);
    }
    @Test void auditFailureRollsBackParentAndStandardTogether()throws Exception {
        long parts=scalar("SELECT count(*) FROM mst.bsn_part"),standards=scalar("SELECT count(*) FROM mst.bsn_part_std_hist");
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK (tbl_nm != 'mst.bsn_part_std_hist') NOT VALID");
        assertThatThrownBy(()->service.createSalesPart(input("새 파트","2026-11"))).isInstanceOf(RuntimeException.class);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part")).isEqualTo(parts);assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist")).isEqualTo(standards);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isZero();
    }
    @Test void noClosingHistoryUsesSeoulMonthAndRejectsEarlierStart()throws Exception {
        sql("DELETE FROM fin.mth_cls");
        var hq=service.listSalesParts(new PageRequest(1,50,null)).items().get(0);
        assertThat(service.getSalesPart(hq.bsn_part_id()).lastClosedMonth()).isNull();assertThat(service.getSalesPart(hq.bsn_part_id()).firstAllowedMonth()).isEqualTo("2026-10");
        assertThatThrownBy(()->service.createSalesPart(input("새 파트","2026-09"))).isInstanceOf(ApiException.class);
        assertThat(service.createSalesPart(input("새 파트","2026-10")).currentStandard()).isNotNull();
    }
}
