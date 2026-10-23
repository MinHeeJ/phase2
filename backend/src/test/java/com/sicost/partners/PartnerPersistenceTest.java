package com.sicost.partners;

import static com.sicost.partners.PartnerModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
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
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class PartnerPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PartnerMapper mapper;
    PartnerService service;
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/partners/PartnerMapper.xml"));factory.afterPropertiesSet();
        var template=new SqlSessionTemplate(factory.getObject());mapper=template.getMapper(PartnerMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,PartnerService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(PartnerMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(PartnerService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
    void execute(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    String string(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getString(1);}}
    PartnerValue value(String kind,String name,String biz) {return new PartnerValue(kind,name,biz,null,null,null,null,null,kind.equals("VEND")?null:"tax@company.co.kr",kind.equals("CUST")?null:"KB",kind.equals("CUST")?null:"123-456-7890",kind.equals("CUST")?null:"회사");}
    PartnerValue changed(PartnerDetail r,String field,String text) {return new PartnerValue(r.ptnr_se_cd(),field.equals("ptnr_nm")?text:r.ptnr_nm(),"ignored",r.ceo_nm(),r.indty_cd(),field.equals("chrg_nm")?text:r.chrg_nm(),r.chrg_tel_no(),r.rmrk_desc(),r.txinv_email_addr(),r.bank_cd(),field.equals("acnt_no")?text:r.acnt_no(),r.dpstr_nm());}
    @Test void anonymousHttpCreateAndUpdatePersistOnlyChangedColumnsAndMaskedAudit()throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new PartnerController(service,json))
            .setControllerAdvice(new com.sicost.common.api.ApiExceptionHandler())
            .addFilters(new com.sicost.common.api.RequestIdFilter()).build();
        mvc.perform(post("/api/partners").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(value("BOTH","등록 상호","123-45-67890"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ptnr_id").value("1"))
            .andExpect(jsonPath("$.data.ptnr_no").value("C-001")).andExpect(jsonPath("$.data.use_yn").value("Y"));
        assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE ptnr_id=1 AND ptnr_no='C-001' AND use_yn='Y'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.ptnr' AND obj_rec_id='1' AND data_chg_ty_cd='INSERT' AND clmn_nm IS NULL AND chg_user_id IS NULL")).isEqualTo(1);
        var before=service.get("1");
        mvc.perform(put("/api/partners/1").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(changed(before,"acnt_no","987-654-3210"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.acnt_no").value("987-654-3210"))
            .andExpect(jsonPath("$.data.ptnr_nm").value("등록 상호"));
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id='1' AND data_chg_ty_cd='UPDATE' AND clmn_nm='acnt_no' AND bfr_val='7890' AND aft_val='3210'")).isEqualTo(1);
        String timestamp=string("SELECT updated_at::text FROM mst.ptnr WHERE ptnr_id=1");
        mvc.perform(put("/api/partners/1").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(changed(service.get("1"),"ptnr_nm","등록 상호"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.biz_reg_no").value("123-45-67890"));
        assertThat(string("SELECT updated_at::text FROM mst.ptnr WHERE ptnr_id=1")).isEqualTo(timestamp);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(2);
        mvc.perform(put("/api/partners/99").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(value("BOTH","없는 상호","223-45-67890"))))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(2);
    }
    @Test void defaultListUsesActiveSortedThreeAndSameCountWithAndFilters()throws Exception {
        service.create(value("CUST","가 거래처","123-45-67890"));service.create(value("VEND","나 거래처","223-45-67890"));service.create(value("BOTH","다 거래처","323-45-67890"));var stopped=service.create(value("VEND","라 중지","423-45-67890"));
        execute("UPDATE mst.ptnr SET use_yn='N' WHERE ptnr_id="+stopped.ptnr_id());
        var page=service.list(Map.of());assertThat(page.items()).extracting(PartnerListItem::ptnr_nm).containsExactly("가 거래처","나 거래처","다 거래처");assertThat(page.total()).isEqualTo(3);assertThat(page.size()).isEqualTo(50);
        var filtered=service.list(Map.of("kind","VEND","q","3234567890"));assertThat(filtered.items()).extracting(PartnerListItem::ptnr_se_cd).containsExactly("BOTH");assertThat(filtered.total()).isEqualTo(1);
        var none=service.list(Map.of("kind","VEND","q","1234567890"));assertThat(none.items()).isEmpty();assertThat(none.total()).isZero();
        assertThat(service.list(Map.of("useYn","N")).total()).isEqualTo(1);assertThat(service.list(Map.of("useYn","")).total()).isEqualTo(4);
    }
    @Test void readsNeverCreateBusinessHistoryOrAccessRecords()throws Exception {
        var row=service.create(value("CUST","읽기 거래처","123-45-67890"));long rows=scalar("SELECT count(*) FROM mst.ptnr"),history=scalar("SELECT count(*) FROM sys.data_chg_hist"),access=scalar("SELECT count(*) FROM sys.acs_hist");
        service.get(row.ptnr_id());service.get(row.ptnr_id());service.list(Map.of());
        assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(rows);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(history);assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access);
    }
    @Test void createUsesIdentityCodeAndSingleAtomicInsertHistory()throws Exception {
        var row=service.create(value("CUST","첫 거래처","123-45-67890"));assertThat(row.ptnr_no()).isEqualTo("C-001");assertThat(row.use_yn()).isEqualTo("Y");assertThat(service.get(row.ptnr_id())).isEqualTo(row);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.ptnr' AND obj_rec_id='"+row.ptnr_id()+"' AND data_chg_ty_cd='INSERT' AND clmn_nm IS NULL AND chg_user_id IS NULL")).isEqualTo(1);
        execute("SELECT setval('mst.seq_ptnr_id',1000,false)");assertThat(service.create(value("VEND","천번째","223-45-67890")).ptnr_no()).isEqualTo("C-1000");
    }
    @Test void sameNameAllowedButStoppedNumberRejected()throws Exception {
        var row=service.create(value("CUST","같은 상호","123-45-67890"));execute("UPDATE mst.ptnr SET use_yn='N' WHERE ptnr_id="+row.ptnr_id());
        assertThatThrownBy(()->service.create(value("CUST","다른 상호","123-45-67890"))).isInstanceOf(ApiException.class).hasMessageContaining("같은 상호");assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(1);
        service.create(value("CUST","같은 상호","223-45-67890"));assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(2);
    }
    @Test void auditFailureRollsBackCreateAndUpdate()throws Exception {
        var row=service.create(value("BOTH","원래 상호","123-45-67890"));
        execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.update(row.ptnr_id(),changed(row,"ptnr_nm","다른 상호"))).isInstanceOf(RuntimeException.class);assertThat(service.get(row.ptnr_id())).isEqualTo(row);
        assertThatThrownBy(()->service.create(value("CUST","신규 상호","223-45-67890"))).isInstanceOf(RuntimeException.class);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(1);
    }
    @Test void noOpPreservesTimestampAndHistoryAndBusinessNumber()throws Exception {
        var row=service.create(value("BOTH","원래 상호","123-45-67890"));service.update(row.ptnr_id(),changed(row,"ptnr_nm","새 상호"));row=service.get(row.ptnr_id());
        String timestamp=string("SELECT updated_at::text FROM mst.ptnr WHERE ptnr_id="+row.ptnr_id());long events=scalar("SELECT count(*) FROM sys.data_chg_hist");
        service.update(row.ptnr_id(),changed(row,"ptnr_nm",row.ptnr_nm()));assertThat(service.get(row.ptnr_id()).biz_reg_no()).isEqualTo("123-45-67890");
        assertThat(string("SELECT updated_at::text FROM mst.ptnr WHERE ptnr_id="+row.ptnr_id())).isEqualTo(timestamp);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(events);
    }
    @Test void updatesOnlyChangedColumnAndAccountAuditIsLastFour()throws Exception {
        var row=service.create(value("BOTH","원래 상호","123-45-67890"));service.update(row.ptnr_id(),changed(row,"ptnr_nm","새 상호"));
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE' AND clmn_nm='ptnr_nm' AND bfr_val='원래 상호' AND aft_val='새 상호'")).isEqualTo(1);
        var renamed=service.get(row.ptnr_id());assertThat(renamed.biz_reg_no()).isEqualTo(row.biz_reg_no());assertThat(renamed.bank_cd()).isEqualTo(row.bank_cd());assertThat(renamed.acnt_no()).isEqualTo(row.acnt_no());
        service.update(row.ptnr_id(),changed(renamed,"acnt_no","987-654-3210"));
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE clmn_nm='acnt_no' AND bfr_val='7890' AND aft_val='3210'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE bfr_val LIKE '%123-456%' OR aft_val LIKE '%987-654%'")).isZero();
    }
    @Test void lastSaveWinsAndRoleRemovalPreservesRelationships()throws Exception {
        var row=service.create(value("BOTH","원래 상호","123-45-67890"));service.update(row.ptnr_id(),changed(row,"ptnr_nm","먼저 상호"));service.update(row.ptnr_id(),changed(row,"ptnr_nm","나중 상호"));assertThat(service.get(row.ptnr_id()).ptnr_nm()).isEqualTo("나중 상호");
        execute("INSERT INTO prj.prj(prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd) VALUES('P-1','프로젝트',"+row.ptnr_id()+",'TK')");
        service.update(row.ptnr_id(),value("VEND","나중 상호",null));assertThat(scalar("SELECT cust_ptnr_id FROM prj.prj WHERE prj_no='P-1'")).isEqualTo(Long.parseLong(row.ptnr_id()));assertThat(string("SELECT txinv_email_addr FROM mst.ptnr WHERE ptnr_id="+row.ptnr_id())).isNull();
    }
    @Test void validSixAssignmentsAreFivePeopleAcrossTwoProjectsAndBothIsUnion()throws Exception {
        var both=service.create(value("BOTH","둘 다","123-45-67890"));var vendor=service.create(value("VEND","협력사","223-45-67890"));
        for(int i=1;i<=7;i++) execute("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,vndr_ptnr_id) VALUES("+i+",'K-"+i+"','인력"+i+"','OS',"+(i==5?vendor.ptnr_id():both.ptnr_id())+")");
        String[] states={"RUN","HOLD","END","LOST"};for(int i=1;i<=4;i++) execute("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES("+i+",'P-"+i+"','프로젝트"+i+"',"+both.ptnr_id()+",'TK','"+states[i-1]+"')");
        for(int i=1;i<=6;i++) assignment(i,i<=3?1:2,i==6?1:i,"ACTIVE","2026-10-22","2026-10-22");
        assignment(7,3,6,"ACTIVE","2026-10-01","2026-10-30");assignment(8,4,7,"ACTIVE","2026-10-01","2026-10-30");assignment(9,1,6,"CANCELLED","2026-10-01","2026-10-30");assignment(10,1,6,"ENDED","2026-10-01","2026-10-30");assignment(11,1,6,"ACTIVE","2026-10-01","2026-10-21");assignment(12,1,7,"ACTIVE","2026-10-23","2026-10-30");
        var item=service.list(Map.of("kind","BOTH")).items().get(0);assertThat(item.projectCount()).isEqualTo(2);assertThat(item.assignmentPeopleCount()).isEqualTo(5);
        assertThat(item.paymentAccount()).isEqualTo("국민은행 …7890");assertThat(json.writeValueAsString(item)).doesNotContain("123-456-7890","acnt_no","dpstr_nm");
        var vend=service.list(Map.of("kind","VEND","q","2234567890")).items().get(0);assertThat(vend.projectCount()).isNull();assertThat(vend.assignmentPeopleCount()).isEqualTo(1);
    }
    void assignment(int id,int project,int dev,String status,String begin,String end)throws Exception {execute("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES('A-"+id+"',"+project+","+dev+",'BE','"+begin+"','"+end+"','"+status+"')");}
    @Test void customerProjectWithoutAssignmentsStillCountsAndAllSortsExecute()throws Exception {
        var row=service.create(value("CUST","프로젝트만","123-45-67890"));execute("INSERT INTO prj.prj(prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES('P-1','예정',"+row.ptnr_id()+",'TK','PLAN')");
        for(String sort:List.of("ptnr_se_cd","ptnr_no","ptnr_nm","biz_reg_no","ceo_nm","indty_cd","chrg_nm","chrg_tel_no","txinv_email_addr","paymentAccount","projectCount","assignmentPeopleCount","use_yn")) {
            var page=service.list(Map.of("sort",sort+",desc"));assertThat(page.items().get(0).projectCount()).isEqualTo(1);assertThat(page.items().get(0).assignmentPeopleCount()).isZero();
        }
    }
}
