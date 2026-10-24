package com.sicost.salespartexport;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.salesparts.*;
import com.sicost.salespartsops.*;
import java.io.ByteArrayInputStream;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class SalesPartExportPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    SalesPartService parts;
    SalesPartLifecycleService lifecycle;
    SalesPartExportService exports;
    SalesPartController creation;
    ObjectMapper json=new ObjectMapper();

    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()) {
            s.execute("CREATE DATABASE "+database+" TEMPLATE template0");
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/salesparts/SalesPartMapper.xml"),
            new ClassPathResource("mappers/salespartsops/SalesPartLifecycleMapper.xml"));factory.afterPropertiesSet();
        var template=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,SalesPartService.class,SalesPartLifecycleService.class,
            SalesPartExportService.class,ExcelExportService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(SalesPartMapper.class,()->template.getMapper(SalesPartMapper.class));
        context.registerBean(SalesPartLifecycleMapper.class,()->template.getMapper(SalesPartLifecycleMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->json);
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();parts=context.getBean(SalesPartService.class);lifecycle=context.getBean(SalesPartLifecycleService.class);
        exports=context.getBean(SalesPartExportService.class);creation=new SalesPartController(parts,json);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()) {
            s.execute("DROP DATABASE "+database+" WITH (FORCE)");
        }
    }
    long scalar(String sql)throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)) {assertThat(r.next()).isTrue();return r.getLong(1);}
    }
    String text(String sql)throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)) {assertThat(r.next()).isTrue();return r.getString(1);}
    }
    String create(String name)throws Exception {
        return creation.createSalesPart(json.writeValueAsString(Map.of("bsn_part_nm",name,"aply_bgn_ym","2026-10",
            "ldr_nm","파일 파트장","prft_dstrb_rt",42.25,"bsn_fee_amt",1234567))).data().bsn_part_id();
    }
    String businessSnapshot()throws Exception {
        return text("SELECT jsonb_build_object('parts',(SELECT jsonb_agg(to_jsonb(p) ORDER BY bsn_part_id) FROM mst.bsn_part p),"
            +"'standards',(SELECT jsonb_agg(to_jsonb(s) ORDER BY part_std_hist_id) FROM mst.bsn_part_std_hist s),"
            +"'history',(SELECT jsonb_agg(to_jsonb(h) ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    @Test void threePageExportUsesPersistedRowsAndCommitsExactlyOneDownloadWithoutBusinessChanges()throws Exception {
        for(int i=1;i<=100;i++)create("파일 영업 파트 "+i);
        String stopped=create("중지 파일 파트");lifecycle.disable(stopped);
        var page=parts.list(new PageRequest(2,50,null));assertThat(page.items()).hasSize(50);assertThat(page.total()).isEqualTo(102);
        String before=businessSnapshot();long access=scalar("SELECT count(*) FROM sys.acs_hist");
        var columns=List.of("bsn_part_nm","currentStandard.bsn_fee_amt","use_yn");
        var file=exports.export(columns,null);
        assertThat(file.filename()).isEqualTo("영업파트_20261023.xlsx");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(file.bytes()))) {
            var sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()-3).isEqualTo(page.total());
            var cell=sheet.getRow(5).getCell(1);assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(cell.getNumericCellValue()).isEqualTo(1234567);assertThat(cell.getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(sheet.getRow(105).getCell(0).getStringCellValue()).isEqualTo("중지 파일 파트");
            assertThat(sheet.getRow(105).getCell(2).getStringCellValue()).isEqualTo("N");
        }
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access+1);
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-15' AND user_id IS NULL AND obj_desc='영업파트_20261023.xlsx'")).isEqualTo(1);
        var detail=json.readTree(text("SELECT dtl_desc FROM sys.acs_hist WHERE obj_desc='영업파트_20261023.xlsx'"));
        assertThat(detail.get("columns")).isEqualTo(json.valueToTree(columns));assertThat(detail.get("ids").isNull()).isTrue();
        assertThat(detail.get("filename").asText()).isEqualTo(file.filename());assertThat(detail.get("conditions").size()).isZero();
        assertThat(businessSnapshot()).isEqualTo(before);
    }
    @Test void selectedRowsCommitOneAuditAndMissingIdentityRejectsWholeFileWithoutAnotherAudit()throws Exception {
        String first=create("첫 파일 파트"),second=create("둘째 파일 파트"),other=create("비선택 파트");lifecycle.disable(second);
        long access=scalar("SELECT count(*) FROM sys.acs_hist");String before=businessSnapshot();
        var file=exports.export(List.of("bsn_part_nm"),List.of(second,first,first));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(file.bytes()))) {
            var sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()-3).isEqualTo(2);
            assertThat(IntStream.rangeClosed(4,5).mapToObj(i->sheet.getRow(i).getCell(0).getStringCellValue()).toList())
                .containsExactly("첫 파일 파트","둘째 파일 파트");
        }
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access+1);
        assertThatThrownBy(()->exports.export(List.of("bsn_part_nm"),List.of(other,"9223372036854775807")))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.field()).isEqualTo("ids"));
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access+1);assertThat(businessSnapshot()).isEqualTo(before);
    }
    @Test void rejectedDownloadAuditRollsBackAndLeavesBusinessDataUnchanged()throws Exception {
        create("원자 다운로드 파트");String before=businessSnapshot();long access=scalar("SELECT count(*) FROM sys.acs_hist");
        try(var c=source.getConnection();var s=c.createStatement()) {
            s.execute("ALTER TABLE sys.acs_hist ADD CONSTRAINT reject_sales_part_download CHECK (acs_ty_cd <> 'DOWNLOAD') NOT VALID");
        }
        assertThatThrownBy(()->exports.export(List.of("bsn_part_nm"),null)).isInstanceOf(RuntimeException.class);
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(access);assertThat(businessSnapshot()).isEqualTo(before);
    }
}
