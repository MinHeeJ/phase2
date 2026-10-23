package com.sicost.salesparts;

import static com.sicost.salesparts.SalesPartModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
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
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(SalesPartMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,SalesPartService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(SalesPartMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(SalesPartService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    SalesPartCreate input(String name,String month) {return new SalesPartCreate(name,month,"파트장",BigDecimal.valueOf(40),1500000L);}
    PartRead read(String id,String month) {return new PartRead(Long.valueOf(id),month,LocalDate.of(2026,10,22));}
    @Test void futureCreationIsAtomicHasTwoHistoriesAndIndependentStoredStandard()throws Exception {
        var row=service.createSalesPart(input("신규","2026-11"));String id=row.bsn_part_id();
        assertThat(row.hq_yn()).isEqualTo("N");assertThat(row.sort_seq()).isEqualTo(1);assertThat(row.currentStandard()).isNull();assertThat(row.scheduledChanges()).hasSize(1);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part WHERE bsn_part_id="+id)).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist WHERE bsn_part_id="+id+" AND ldr_user_id IS NULL AND aply_end_ym IS NULL")).isEqualTo(1);
        String standard=row.standards().get(0).part_std_hist_id();
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE (tbl_nm='mst.bsn_part' AND obj_rec_id='"+id+"') OR (tbl_nm='mst.bsn_part_std_hist' AND obj_rec_id='"+standard+"')")).isEqualTo(2);
        assertThat(mapper.standards(read(id,"2026-11")).get(0).status()).isEqualTo("적용 중");
        sql("UPDATE cmn.oprt_std SET num_val=50 WHERE oprt_std_id='PART_PROFIT_RATE'");
        assertThat(service.getSalesPart(id).standards()).isEqualTo(row.standards());
        assertThat(service.listSalesParts(new PageRequest(1,50,null)).items()).anyMatch(p->p.bsn_part_id().equals(id));
    }
    @Test void currentFuturePastAndAggregatesAreDatabaseBackedAndReadsDoNotWrite()throws Exception {
        var row=service.createSalesPart(input("영업","2026-10"));String id=row.bsn_part_id();
        sql("UPDATE mst.bsn_part_std_hist SET aply_end_ym='2026-10' WHERE bsn_part_id="+id);
        sql("INSERT INTO mst.bsn_part_std_hist(bsn_part_id,aply_bgn_ym,aply_end_ym,ldr_nm,prft_dstrb_rt,bsn_fee_amt) VALUES ("+id+",'2026-01','2026-09','이전',40,1500000),("+id+",'2026-11',NULL,'파트장',45,1500000)");
        sql("INSERT INTO fin.part_stlm(bsn_part_id,stlm_ym,part_std_hist_id,prft_dstrb_rt,bsn_fee_amt,stlm_stts_cd) SELECT bsn_part_id,'2026-09',part_std_hist_id,prft_dstrb_rt,bsn_fee_amt,'CONFIRM' FROM mst.bsn_part_std_hist WHERE bsn_part_id="+id+" AND aply_bgn_ym='2026-01'");
        sql("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,bsn_part_id,rsgn_dt,use_yn) VALUES ('P1','재직','FT',"+id+",NULL,'Y'),('P2','오늘퇴사','FT',"+id+",'2026-10-22','Y'),('P3','이전퇴사','FT',"+id+",'2026-10-21','Y'),('P4','중지','FT',"+id+",NULL,'N'),('P5','이후퇴사','FT',"+id+",'2026-10-23','Y')");
        sql("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES ('P1','계획','TK','PLAN',"+id+"),('P2','진행','TK','RUN',"+id+"),('P3','보류','TK','HOLD',"+id+"),('P4','종료','TK','END',"+id+")");
        var stopped=service.createSalesPart(input("중지","2026-10"));sql("UPDATE mst.bsn_part SET use_yn='N' WHERE bsn_part_id="+stopped.bsn_part_id());
        long histories=scalar("SELECT count(*) FROM sys.data_chg_hist"),standards=scalar("SELECT count(*) FROM mst.bsn_part_std_hist"),access=scalar("SELECT count(*) FROM sys.acs_hist");
        var list=service.listSalesParts(new PageRequest(1,50,null));assertThat(list.total()).isEqualTo(3);assertThat(list.items()).anyMatch(p->p.hq_yn().equals("Y")&&p.currentStandard().bsn_fee_amt()==0);
        var detail=service.getSalesPart(id);assertThat(detail.peopleCount()).isEqualTo(2);assertThat(detail.projectCount()).isEqualTo(3);assertThat(detail.currentStandard().prft_dstrb_rt()).isEqualByComparingTo("40");assertThat(detail.scheduledChanges().get(0).prft_dstrb_rt()).isEqualByComparingTo("45");
        assertThat(detail.standards()).extracting(SalesPartStandard::status).containsExactly("예정","적용 중","지난 기준");
        assertThat(service.getSalesPart(stopped.bsn_part_id()).use_yn()).isEqualTo("N");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(histories);assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist")).isEqualTo(standards);assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access);
        assertThat(scalar("SELECT prft_dstrb_rt FROM fin.part_stlm WHERE bsn_part_id="+id)).isEqualTo(40);
        for(String sort:List.of("bsn_part_nm,asc","currentStandard.ldr_nm,asc","currentStandard.prft_dstrb_rt,desc","currentStandard.bsn_fee_amt,asc","currentStandard.aply_bgn_ym,asc","peopleCount,desc","projectCount,desc","use_yn,asc","sort_seq,asc")) assertThat(service.listSalesParts(new PageRequest(1,20,sort)).total()).isEqualTo(3);
    }
    @Test void duplicateAndClosedMonthDoNotInsert()throws Exception {
        service.createSalesPart(input("영업","2026-10"));long count=mapper.count(),history=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.createSalesPart(input("영업","2026-11"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.createSalesPart(input("신규","2026-09"))).isInstanceOf(ApiException.class);
        assertThat(mapper.count()).isEqualTo(count);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);
    }
    @Test void failedSecondAuditRollsBackHeaderStandardAndFirstAudit()throws Exception {
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_standard_history CHECK (tbl_nm &lt;&gt; 'mst.bsn_part_std_hist') NOT VALID".replace("&lt;&gt;","<>"));
        long count=mapper.count(),history=scalar("SELECT count(*) FROM sys.data_chg_hist"),standards=scalar("SELECT count(*) FROM mst.bsn_part_std_hist");
        assertThatThrownBy(()->service.createSalesPart(input("실패","2026-11"))).isInstanceOf(RuntimeException.class);
        assertThat(mapper.count()).isEqualTo(count);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist")).isEqualTo(standards);
    }
    @Test void noCloseHasNoFabricatedBoundaryAndAllowsExplicitMonth()throws Exception {
        sql("DELETE FROM fin.mth_cls");var detail=service.createSalesPart(input("영업","2025-01"));assertThat(detail.lastClosedMonth()).isNull();assertThat(detail.firstAllowedMonth()).isNull();
    }
}
