package com.sicost.partnersops;

import static com.sicost.partnersops.PartnerActivityModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class PartnerActivityPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PartnerActivityService service;
    PartnerActivityMapper mapper;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){s.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/partners/PartnerActivityMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(PartnerActivityMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,PartnerActivityService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class);
        context.registerBean(PartnerActivityMapper.class,()->mapper);context.registerBean(ObjectMapper.class,()->new ObjectMapper());
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));context.refresh();service=context.getBean(PartnerActivityService.class);
        execute("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no,bank_cd,acnt_no,dpstr_nm,use_yn) VALUES (1,'C-001','CUST','고객사 A','100-00-00001',NULL,NULL,NULL,'Y'),(2,'C-002','VEND','협력사 B','100-00-00002','KB','012-345-678901','예금주','Y'),(3,'C-003','BOTH','둘 다','100-00-00003','KB','012-345-678901','예금주','Y'),(4,'C-004','CUST','중지 고객','100-00-00004',NULL,NULL,NULL,'N')");
        execute("SELECT setval('mst.seq_ptnr_id',4)");
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {if(context!=null)context.close();if(source==null)return;try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){s.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
    void execute(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    long scalar(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    String snapshot(String table)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COALESCE(json_agg(x ORDER BY x::text)::text,'[]') FROM (SELECT to_jsonb(t) x FROM "+table+" t) a")){r.next();return r.getString(1);}}
    @Test void optionsKindOrderAndReadsHaveNoSideEffects()throws Exception {
        String before=snapshot("mst.ptnr");assertThat(service.options("CUST")).extracting(PartnerOption::name).containsExactly("고객사 A","둘 다");
        assertThat(service.options("VEND")).extracting(PartnerOption::id).containsExactly("3","2");assertThat(snapshot("mst.ptnr")).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isZero();assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isZero();
    }
    @Test void disableEnablePreserveLinksAndCodesAndHaveOnlyActualChangeHistory()throws Exception {
        execute("INSERT INTO prj.prj(prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES ('P-old','과거 프로젝트',1,'TK','END')");
        String links=snapshot("prj.prj");var result=service.disable(List.of("1","3","1"));assertThat(result.disabledIds()).containsExactly("1","3");
        assertThat(service.options("CUST")).isEmpty();assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='DISABLE' AND chg_user_id IS NULL")).isEqualTo(2);
        service.disable(List.of("1"));assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(2);
        var detail=service.enable("1");assertThat(detail.get("ptnr_id")).isEqualTo("1");assertThat(detail.get("ptnr_no")).isEqualTo("C-001");assertThat(detail.get("biz_reg_no")).isEqualTo("100-00-00001");
        assertThat(service.options("CUST")).extracting(PartnerOption::id).containsExactly("1");service.enable("1");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE' AND clmn_nm='use_yn' AND bfr_val='N' AND aft_val='Y'")).isEqualTo(1);assertThat(snapshot("prj.prj")).isEqualTo(links);
    }
    @Test void missingBatchMemberCausesNoPartialMutation()throws Exception {String before=snapshot("mst.ptnr");assertThatThrownBy(()->service.disable(List.of("1","99"))).isInstanceOf(ApiException.class);assertThat(snapshot("mst.ptnr")).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isZero();}
    @Test void affiliatedPeopleAndCustomerProjectsBlockButTodayResignationDoesNot()throws Exception {
        execute("INSERT INTO mst.dev(mng_no,dev_nm,emp_se_cd,vndr_ptnr_id,rsgn_dt) VALUES ('K-1','인력1','OS',2,NULL),('K-2','인력2','OS',2,NULL),('K-3','인력3','OS',2,'2026-10-23'),('K-4','오늘퇴사','OS',3,'2026-10-22')");
        var result=service.disable(List.of("1","2","3"));assertThat(result.disabledIds()).containsExactly("1","3");assertThat(result.blocked().get(0).peopleCount()).isEqualTo(3);
        assertThatThrownBy(()->service.disable(List.of("2"))).isInstanceOf(ApiException.class).hasMessageContaining("3명");assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE obj_rec_id='2'")).isZero();
    }
    @Test void progressCustomerProjectBlocksWithoutAnyAssignment()throws Exception {execute("INSERT INTO prj.prj(prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES ('P-run','진행',1,'TK','RUN')");assertThatThrownBy(()->service.disable(List.of("1"))).isInstanceOf(ApiException.class).hasMessageContaining("프로젝트 1건");assertThat(mapper.partner("1").use_yn()).isEqualTo("Y");}
    void assignmentFixture()throws Exception {
        execute("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES (1,'P-1','프로젝트 A',1,'TK','RUN'),(2,'P-2','프로젝트 B',1,'TK','HOLD'),(3,'P-3','종료',1,'TK','END')");
        execute("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,vndr_ptnr_id) VALUES (1,'K-1','이름 A','OS',2),(2,'K-2','이름 B','OS',2),(3,'K-3','자사 인력','FT',NULL)");
        execute("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES ('A-1',1,1,'BE','2026-10-01','2026-10-31','ACTIVE'),('A-2',2,2,'BE','2026-10-01','2026-10-22','ACTIVE'),('A-3',1,1,'BE','2026-10-01','2026-10-21','ACTIVE'),('A-4',3,1,'BE','2026-10-01','2026-10-31','ACTIVE'),('A-5',1,1,'BE','2026-10-01','2026-10-31','CANCELLED'),('A-6',1,3,'BE','2026-10-01','2026-10-31','ACTIVE')");
    }
    @Test void assignmentsUseCurrentWindowStatusKindAndAffiliationWithoutWrites()throws Exception {
        assignmentFixture();String before=snapshot("asgn.asgn");var rows=service.assignments("2");assertThat(rows).hasSize(2);assertThat(rows).extracting(PartnerAssignment::affiliation).containsOnly("협력사 B");assertThat(rows).extracting(PartnerAssignment::projectName).containsExactly("프로젝트 A","프로젝트 B");assertThat(service.assignments("1")).extracting(PartnerAssignment::affiliation).contains("자사");assertThat(snapshot("asgn.asgn")).isEqualTo(before);
    }
    @Test void historyIsNewestFirstTranslatedMaskedAndReadOnly()throws Exception {
        execute("INSERT INTO sys.data_chg_hist(tbl_nm,obj_rec_id,data_chg_ty_cd,clmn_nm,bfr_val,aft_val,chg_at) VALUES ('mst.ptnr','2','UPDATE','acnt_no','012345678901','998877665544','2026-10-22 01:00:00'),('mst.ptnr','2','UPDATE','bank_cd','KB','SHINHAN','2026-10-22 02:00:00'),('mst.dev','2','UPDATE','dev_nm','이전','이후','2026-10-22 03:00:00')");
        String before=snapshot("sys.data_chg_hist");var rows=service.history("2");assertThat(rows).hasSize(2);assertThat(rows.get(0).itemName()).isEqualTo("은행");assertThat(rows.get(0).after()).isEqualTo("신한은행");assertThat(rows.get(1).before()).isEqualTo("8901");assertThat(rows.get(1).after()).isEqualTo("5544");assertThat(rows.get(1).actorName()).isEqualTo("기본 사용자");assertThat(snapshot("sys.data_chg_hist")).isEqualTo(before);
    }
    @Test void auditFailureRollsBackStateAndDoesNotRemoveLinks()throws Exception {String before=snapshot("mst.ptnr");execute("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK (false) NOT VALID");assertThatThrownBy(()->service.disable(List.of("1","3"))).isInstanceOf(RuntimeException.class);assertThat(snapshot("mst.ptnr")).isEqualTo(before);}
    @Test void exportAllPagesSelectionAndAuditReadback()throws Exception {
        execute("INSERT INTO mst.ptnr(ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) SELECT 'C-X'||n,'CUST','대상 '||n,'200-00-'||lpad(n::text,5,'0') FROM generate_series(1,105) n");
        String before=snapshot("mst.ptnr");var file=service.export("CUST","Y","대상",List.of("biz_reg_no","ptnr_nm"),null);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(file.bytes()))){assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(108);assertThat(book.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("사업자번호");}
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-15' AND user_id IS NULL AND obj_desc='거래처_20261022.xlsx' AND (dtl_desc::jsonb->>'rowCount')::int=105")).isEqualTo(1);
        var selected=service.export(null,"Y",null,List.of("paymentAccount"),List.of("2","3"));try(var book=new XSSFWorkbook(new ByteArrayInputStream(selected.bytes()))){assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(5);assertThat(book.getSheetAt(0).getRow(4).getCell(1).getStringCellValue()).isEqualTo("012-345-678901");}
        assertThat(snapshot("mst.ptnr")).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isZero();
    }
    @Test void exportSearchMatchesListForLiteralNamesFullBusinessNumbersAndWhitespace()throws Exception {
        execute("INSERT INTO mst.ptnr(ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES ('C-005','CUST','literal%_name','200-00-00005')");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/partners/PartnerMapper.xml"));factory.afterPropertiesSet();
        var listMapper=new SqlSessionTemplate(factory.getObject()).getMapper(com.sicost.partners.PartnerMapper.class);
        for(String term:List.of("%","_","10000","1000000001","  고객사  "," 100-00-00002 ")) {
            var query=com.sicost.partners.PartnerQuery.of(Map.of("useYn","","q",term),LocalDate.of(2026,10,22));
            var expected=listMapper.list(query).stream().map(com.sicost.partners.PartnerModels.PartnerListItem::ptnr_no).toList();
            var file=service.export(null,"",term,List.of("ptnr_no"),null);
            try(var book=new XSSFWorkbook(new ByteArrayInputStream(file.bytes()))) {
                var sheet=book.getSheetAt(0);List<String> actual=new ArrayList<>();
                for(int row=4;row<=sheet.getLastRowNum();row++)actual.add(sheet.getRow(row).getCell(0).getStringCellValue());
                assertThat(actual).as("query=%s",term).containsExactlyElementsOf(expected);
            }
        }
    }
}
