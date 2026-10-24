package com.sicost.salesparts;

import static com.sicost.salesparts.SalesPartModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import com.sicost.common.api.PageRequest;
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
        var template=new SqlSessionTemplate(factory.getObject());mapper=template.getMapper(SalesPartMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,SalesPartService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(SalesPartMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(SalesPartService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    long scalar(String sql)throws Exception {try(var connection=source.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    void execute(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    SalesPartCreate input(String name,String month) {return new SalesPartCreate(name,month,"파트장",new BigDecimal("45"),1500000);}
    @Test void futureCreationAndAuditsPersistIndependentlyOfOperatingParameters()throws Exception {
        long before=scalar("SELECT count(*) FROM sys.data_chg_hist");
        var created=service.create(input("신규 영업 파트","2026-11"));String id=created.bsn_part_id();
        assertThat(created.currentStandard()).isNull();assertThat(created.scheduledChanges()).hasSize(1);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part WHERE bsn_part_id="+id+" AND hq_yn='N' AND use_yn='Y'")).isEqualTo(1);
        assertThat(scalar("SELECT sort_seq FROM mst.bsn_part WHERE bsn_part_id="+id)).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist WHERE bsn_part_id="+id+" AND ldr_user_id IS NULL AND aply_end_ym IS NULL")).isEqualTo(1);
        assertThat(mapper.standards(Long.parseLong(id),"2026-11").get(0).status()).isEqualTo("적용 중");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(before+2);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.bsn_part_std_hist' AND data_chg_ty_cd='INSERT' AND aft_val LIKE '%2026-11%파트장%45%1500000%'")).isEqualTo(1);
        execute("UPDATE cmn.oprt_std SET num_val=1 WHERE oprt_std_id IN ('PART_PROFIT_RATE','PART_SALES_FEE')");
        assertThat(service.get(id).standards()).isEqualTo(created.standards());
    }
    @Test void listAndDetailReadOnlyWithThreeHistoryStatesAndDisabledPart()throws Exception {
        var first=service.create(input("영업 파트","2026-10"));var second=service.create(input("중지 파트","2026-10"));
        execute("UPDATE mst.bsn_part SET use_yn='N' WHERE bsn_part_id="+second.bsn_part_id());
        execute("UPDATE mst.bsn_part_std_hist SET aply_end_ym='2026-10' WHERE bsn_part_id="+first.bsn_part_id());
        execute("INSERT INTO mst.bsn_part_std_hist(bsn_part_id,aply_bgn_ym,aply_end_ym,ldr_nm,prft_dstrb_rt,bsn_fee_amt) VALUES ("+first.bsn_part_id()+",'2026-01','2026-09','이전',35,1500000),("+first.bsn_part_id()+",'2026-11',NULL,'예정',45,1500000)");
        var before=service.get(first.bsn_part_id());long history=scalar("SELECT count(*) FROM sys.data_chg_hist"),access=scalar("SELECT count(*) FROM sys.acs_hist");
        var list=service.list(new PageRequest(1,50,null));assertThat(list.items()).hasSize(3);assertThat(list.total()).isEqualTo(3);
        assertThat(list.items()).anyMatch(r->r.hq_yn().equals("Y"));assertThat(list.items()).anyMatch(r->r.use_yn().equals("N"));
        assertThat(before.standards()).extracting(SalesPartStandard::status).containsExactly("예정","적용 중","지난 기준");
        assertThat(before.currentStandard().aply_bgn_ym()).isEqualTo("2026-10");
        assertThat(service.get(first.bsn_part_id())).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access);
        assertThat(service.get(second.bsn_part_id()).use_yn()).isEqualTo("N");
        assertThat(service.get(first.bsn_part_id()).lastClosedMonth()).isEqualTo("2026-09");
    }
    @Test void countsExcludeInactiveAndTodayResignedPeopleAndFinishedProjects()throws Exception {
        var part=service.create(input("집계 파트","2026-10"));String id=part.bsn_part_id();
        execute("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,bsn_part_id,use_yn,rsgn_dt) VALUES ('COUNT1','활성','FT',"+id+",'Y',NULL),('COUNT2','오늘퇴사','FT',"+id+",'Y','2026-10-22'),('COUNT3','중지','FT',"+id+",'N',NULL),('COUNT4','내일퇴사','FT',"+id+",'Y','2026-10-23')");
        execute("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES ('COUNT1','계획','TK','PLAN',"+id+"),('COUNT2','완료','TK','END',"+id+")");
        assertThat(service.get(id).peopleCount()).isEqualTo(2);assertThat(service.get(id).projectCount()).isEqualTo(1);
        var sorted=service.list(new PageRequest(1,20,"peopleCount,desc"));assertThat(sorted.total()).isEqualTo(2);assertThat(sorted.items().get(0).bsn_part_id()).isEqualTo(id);
    }
    @Test void closedAndDuplicateCreationsLeaveAllTablesUnchanged()throws Exception {
        service.create(input("중복 파트","2026-11"));long parts=mapper.partCount(),standards=scalar("SELECT count(*) FROM mst.bsn_part_std_hist"),history=scalar("SELECT count(*) FROM sys.data_chg_hist");
        assertThatThrownBy(()->service.create(input("중복 파트","2026-11"))).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThatThrownBy(()->service.create(input("마감 파트","2026-09"))).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThat(mapper.partCount()).isEqualTo(parts);assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist")).isEqualTo(standards);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);
    }
    @Test void auditFailureRollsBackHeaderAndFirstStandard()throws Exception {
        long parts=mapper.partCount(),standards=scalar("SELECT count(*) FROM mst.bsn_part_std_hist");
        execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_part_history CHECK (false) NOT VALID");
        assertThatThrownBy(()->service.create(input("실패 파트","2026-11"))).isInstanceOf(RuntimeException.class);
        assertThat(mapper.partCount()).isEqualTo(parts);assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist")).isEqualTo(standards);
    }
    @Test void withoutClosingExplicitMonthIsAcceptedAndBoundsStayNull()throws Exception {
        execute("UPDATE fin.mth_cls SET cls_stts_cd='OPEN'");var part=service.create(input("과거 파트","2025-01"));
        assertThat(part.lastClosedMonth()).isNull();assertThat(part.firstAllowedMonth()).isNull();assertThat(part.currentStandard()).isNotNull();
    }
    @Test void secondAuditFailureRollsBackHeaderStandardAndFirstAudit()throws Exception {
        long parts=mapper.partCount(),standards=scalar("SELECT count(*) FROM mst.bsn_part_std_hist"),history=scalar("SELECT count(*) FROM sys.data_chg_hist");
        execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_standard_history CHECK (tbl_nm <> 'mst.bsn_part_std_hist') NOT VALID");
        assertThatThrownBy(()->service.create(input("원자 저장 파트","2026-11"))).isInstanceOf(RuntimeException.class);
        assertThat(mapper.partCount()).isEqualTo(parts);
        assertThat(scalar("SELECT count(*) FROM mst.bsn_part_std_hist")).isEqualTo(standards);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);
    }
    @Test void sortingUsesMaximumIncludingDisabledPartsButExcludesHeadquarters()throws Exception {
        var disabled=service.create(input("정렬 기준 파트","2026-11"));
        execute("UPDATE mst.bsn_part SET sort_seq=7,use_yn='N' WHERE bsn_part_id="+disabled.bsn_part_id());
        execute("UPDATE mst.bsn_part SET sort_seq=100 WHERE hq_yn='Y'");
        var created=service.create(input("다음 순서 파트","2026-11"));
        assertThat(created.sort_seq()).isEqualTo(8);
        assertThat(created.hq_yn()).isEqualTo("N");
        assertThat(created.standards()).singleElement().satisfies(s->assertThat(s.aply_end_ym()).isNull());
    }
    @Test void readPagesUseStableIdTiesAndKeepDisabledPartsOnLaterPages()throws Exception {
        var ids=new ArrayList<String>();
        for(int i=0;i<21;i++) ids.add(service.create(input("목록 파트 "+i,"2026-10")).bsn_part_id());
        execute("UPDATE mst.bsn_part SET sort_seq=1 WHERE hq_yn='N'");
        execute("UPDATE mst.bsn_part SET use_yn='N' WHERE bsn_part_id="+ids.get(20));
        var first=service.list(new PageRequest(1,20,null));
        var second=service.list(new PageRequest(2,20,null));
        assertThat(first.total()).isEqualTo(22);
        assertThat(first.items()).hasSize(20);
        assertThat(first.items().get(0).hq_yn()).isEqualTo("Y");
        assertThat(first.items().subList(1,20)).extracting(SalesPartListItem::bsn_part_id).containsExactlyElementsOf(ids.subList(0,19));
        assertThat(second.items()).extracting(SalesPartListItem::bsn_part_id).containsExactlyElementsOf(ids.subList(19,21));
        assertThat(second.items().get(1).use_yn()).isEqualTo("N");
        for(String key:List.of("bsn_part_nm","currentStandard.ldr_nm","currentStandard.prft_dstrb_rt","currentStandard.bsn_fee_amt","currentStandard.aply_bgn_ym","peopleCount","projectCount","use_yn")) {
            var sorted=service.list(new PageRequest(1,50,key+",asc"));
            assertThat(sorted.total()).isEqualTo(22);
            assertThat(sorted.items()).hasSize(22);
        }
    }
    @Test void listAndDetailAgreeOnAllRunningStatesAndResignationBoundaries()throws Exception {
        var part=service.create(input("집계 경계 파트","2026-10"));String id=part.bsn_part_id();
        execute("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,bsn_part_id,use_yn,rsgn_dt) VALUES ('READ1','재직','FT',"+id+",'Y',NULL),('READ2','당일퇴사','FT',"+id+",'Y','2026-10-22'),('READ3','전일퇴사','FT',"+id+",'Y','2026-10-21'),('READ4','다음날퇴사','FT',"+id+",'Y','2026-10-23'),('READ5','미사용','FT',"+id+",'N',NULL)");
        execute("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,bsn_part_id) VALUES ('READ1','계획','TK','PLAN',"+id+"),('READ2','진행','TK','RUN',"+id+"),('READ3','보류','TK','HOLD',"+id+"),('READ4','완료','TK','END',"+id+")");
        var detail=service.get(id);
        var row=service.list(new PageRequest(1,50,null)).items().stream().filter(p->p.bsn_part_id().equals(id)).findFirst().orElseThrow();
        assertThat(detail.peopleCount()).isEqualTo(2);
        assertThat(detail.projectCount()).isEqualTo(3);
        assertThat(row.peopleCount()).isEqualTo(detail.peopleCount());
        assertThat(row.projectCount()).isEqualTo(detail.projectCount());
        assertThat(row.currentStandard()).isEqualTo(detail.currentStandard());
    }
}
