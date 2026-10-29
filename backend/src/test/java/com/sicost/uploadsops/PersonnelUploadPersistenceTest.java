package com.sicost.uploadsops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.shared.SharedReadMapper;
import com.sicost.shared.SharedReadService;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class PersonnelUploadPersistenceTest {
    String adminUrl,user,password,database;DriverManagerDataSource source;UploadService upload;PersonnelUploadService service;
    @TempDir Path directory;
    org.springframework.test.web.servlet.MockMvc mvc;
    @BeforeEach void database()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/uploads/UploadMapper.xml"),new ClassPathResource("mappers/uploads/PersonnelUploadMapper.xml"),new ClassPathResource("mappers/shared/SharedReadMapper.xml"));factory.afterPropertiesSet();
        var session=new SqlSessionTemplate(factory.getObject());var mapper=session.getMapper(PersonnelUploadMapper.class);var legacy=session.getMapper(UploadMapper.class);var manager=new DataSourceTransactionManager(source);Clock clock=Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));var writer=new UploadWorkbookWriter();
        service=new PersonnelUploadService(mapper,new PersonnelUploadForm(mapper,new SharedReadService(session.getMapper(SharedReadMapper.class))),writer,new CurrentUser(),clock,manager);
        ReflectionTestUtils.setField(service,"legacy",legacy);ReflectionTestUtils.setField(service,"json",new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(service,"inspections",new UploadInspectionContexts(clock));
        upload=new UploadService(legacy,new CurrentUser(),clock,writer,new PartnerUploadForm(legacy),manager);ReflectionTestUtils.setField(upload,"directory",directory.toString());ReflectionTestUtils.setField(upload,"personnel",service);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new UploadController(upload,new com.sicost.common.security.PermissionService()))
            .setControllerAdvice(new com.sicost.common.api.ApiExceptionHandler())
            .addFilters(new com.sicost.common.api.RequestIdFilter()).build();
    }
    @AfterEach void cleanup()throws Exception{if(source==null)return;try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
    long scalar(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    void execute(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    PersonnelUploadModels.Result validate(String form,List<Map<String,String>> rows)throws Exception{return upload.validatePersonnel(form,form.equals("B.1")?"2026-10":null,new MockMultipartFile("file","업로드.xlsx","application/octet-stream",PersonnelUploadApiTest.workbook(form,rows)));}
    Map<String,String> developer(String name){return new LinkedHashMap<>(Map.of("이름",name,"직원구분","FT","주 직무","BE","직위","TL"));}
    String businessSnapshot() throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(
            "SELECT json_build_array((SELECT json_agg(d ORDER BY dev_id) FROM mst.dev d),"
            +"(SELECT json_agg(m ORDER BY mth_cst_id) FROM cst.mth_cst m),"
            +"(SELECT json_agg(e ORDER BY expns_id) FROM cst.drct_expns e),"
            +"(SELECT json_agg(u ORDER BY upld_id) FROM sys.upld u),"
            +"(SELECT json_agg(e ORDER BY upld_err_id) FROM sys.upld_err e),"
            +"(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h),"
            +"(SELECT json_agg(a ORDER BY asgn_id) FROM asgn.asgn a),"
            +"(SELECT json_agg(m ORDER BY asgn_id,work_ym) FROM asgn.asgn_mth m),"
            +"(SELECT json_agg(p ORDER BY asgn_id,aply_bgn_dt) FROM asgn.untprc_hist p),"
            +"(SELECT json_agg(h ORDER BY asgn_chg_id) FROM asgn.asgn_chg_hist h))::text")) {
            assertThat(r.next()).isTrue(); return r.getString(1);
        }
    }
    @Test void anonymousValidationAndRevalidationKeepBusinessAndUploadLedgersUnchanged() throws Exception {
        String before=businessSnapshot();
        var response=mvc.perform(multipart("/api/uploads/validate")
            .file(new MockMultipartFile("file","인력.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                PersonnelUploadApiTest.workbook("B.8",List.of(developer("검사 대상")))))
            .param("form_type","B.8"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.row_count").value(1))
            .andExpect(jsonPath("$.data.can_apply").value(true)).andReturn();
        long id=new ObjectMapper().readTree(response.getResponse().getContentAsByteArray()).path("data").path("upload_id").asLong();
        assertThat(businessSnapshot()).isEqualTo(before);
        mvc.perform(post("/api/uploads/{uploadId}/revalidate",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.error_count").value(0)).andExpect(jsonPath("$.data.can_apply").value(true));
        assertThat(businessSnapshot()).isEqualTo(before);
        mvc.perform(post("/api/uploads/{uploadId}/apply",id)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STALE_VALIDATION"));
        assertThat(businessSnapshot()).isEqualTo(before);
    }
    @Test void rowOrderedImportAndAuditRoundTrip()throws Exception {
        long before=scalar("SELECT count(*) FROM mst.dev");var review=validate("B.8",List.of(developer("첫째"),developer("둘째"),developer("셋째")));assertThat(review.error_count()).isZero();assertThat(scalar("SELECT count(*) FROM mst.dev")).isEqualTo(before);
        mvc.perform(post("/api/uploads/{uploadId}/apply",review.upload_id())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applied_count").value(3));
        var applied=service.apply(review.upload_id());assertThat(scalar("SELECT count(*) FROM mst.dev WHERE upld_id="+review.upload_id())).isEqualTo(3);
        try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT dev_id,mng_no,dev_nm FROM mst.dev WHERE upld_id="+review.upload_id()+" ORDER BY dev_id")){for(String name:List.of("첫째","둘째","셋째")){assertThat(rows.next()).isTrue();assertThat(rows.getString("dev_nm")).isEqualTo(name);assertThat(rows.getString("mng_no")).isEqualTo(com.sicost.common.validation.DeveloperManagementNumbers.allocated(rows.getLong("dev_id")));}}
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.dev' AND rsn_desc='엑셀 업로드 #"+review.upload_id()+"'")).isEqualTo(3);assertThat(service.apply(review.upload_id())).isEqualTo(applied);
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='UPLOAD'")).isEqualTo(1);
    }
    @Test void rowsReadHasNoSalaryExpenseAssignmentOrHistorySideEffects() throws Exception {
        long id=validate("B.8",List.of(developer("행 조회"))).upload_id();
        String before=businessSnapshot();
        mvc.perform(get("/api/uploads/{uploadId}/rows",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].row").value(5));
        assertThat(businessSnapshot()).isEqualTo(before);
    }
    @Test void issuesReadHasNoSalaryExpenseAssignmentOrHistorySideEffects() throws Exception {
        var invalid=developer("오류 조회"); invalid.put("주 직무","");
        long id=validate("B.8",List.of(invalid)).upload_id();
        String before=businessSnapshot();
        mvc.perform(get("/api/uploads/{uploadId}/issues",id).param("severity","ERROR"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].column").value("주 직무"))
            .andExpect(jsonPath("$.data.items[0].message").isNotEmpty());
        assertThat(businessSnapshot()).isEqualTo(before);
    }
    @Test void errorDownloadHasNoSalaryExpenseAssignmentOrHistorySideEffects() throws Exception {
        var invalid=developer("오류 파일"); invalid.put("주 직무","");
        long id=validate("B.8",List.of(invalid)).upload_id();
        String before=businessSnapshot();
        var response=mvc.perform(get("/api/uploads/{uploadId}/errors",id)).andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn().getResponse();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheet("오류 목록").getRow(1).getCell(2).getStringCellValue()).isEqualTo("주 직무");
        }
        assertThat(businessSnapshot()).isEqualTo(before);
    }
    @Test void templateDownloadHasNoSalaryExpenseAssignmentOrHistorySideEffects() throws Exception {
        String before=businessSnapshot();
        var response=mvc.perform(get("/api/uploads/templates/B.8")).andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn().getResponse();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(3);
        }
        assertThat(businessSnapshot()).isEqualTo(before);
    }
    @Test void generatedCostsOverwriteProvenanceAndSnapshotAreDatabaseOwned()throws Exception {
        assertThat(scalar("SELECT count(*) FROM fin.mth_cls WHERE cls_ym='2026-10' AND cls_stts_cd='OPEN'")).isEqualTo(1);assertThat(scalar("SELECT CAST(attr1_val AS bigint) FROM cmn.cd_dtl WHERE cd_grp_id='POSITION' AND dtl_cd='TL'")).isEqualTo(200000);assertThat(scalar("SELECT num_val FROM cmn.oprt_std WHERE oprt_std_id='VAT_RATE' ORDER BY aply_bgn_ym DESC LIMIT 1")).isEqualTo(10);
        var people=service.apply(validate("B.8",List.of(developer("첫째"),developer("둘째"),developer("셋째"))).upload_id());List<Map<String,String>> salary=new ArrayList<>();
        for(int i=0;i<people.developer_ids().size();i++){String name=List.of("첫째","둘째","셋째").get(i);salary.add(new LinkedHashMap<>(Map.of("관리번호",com.sicost.common.validation.DeveloperManagementNumbers.allocated(people.developer_ids().get(i)),"이름",name,"월 급여","4000000","4대보험(회사)","420000","팀비","50000")));}
        var initial=service.apply(validate("B.1",salary).upload_id());assertThat(scalar("SELECT count(*) FROM cst.mth_cst WHERE indv_lbr_cst_amt=4970000 AND alwnc_amt=200000 AND rtrpay_acml_amt=350000")).isEqualTo(3);
        long developer=people.developer_ids().get(0);execute("INSERT INTO cst.drct_expns(expns_ym,dev_id,expns_item_cd,expns_amt) VALUES('2026-10',"+developer+",'TEAM',777),('2026-08',"+developer+",'TEAM',888)");
        var updated=service.apply(validate("B.1",salary.subList(0,2)).upload_id());assertThat(updated.ovwr_yn()).isEqualTo("Y");assertThat(scalar("SELECT count(*) FROM cst.mth_cst WHERE cst_ym='2026-10'")).isEqualTo(2);assertThat(scalar("SELECT count(*) FROM cst.drct_expns WHERE upld_id="+initial.upload_id())).isZero();assertThat(scalar("SELECT count(*) FROM cst.drct_expns WHERE expns_amt IN (777,888)")).isEqualTo(2);
        execute("UPDATE mst.dev SET emp_se_cd='FL',pstn_cd=NULL WHERE dev_id="+developer);assertThat(scalar("SELECT count(*) FROM cst.mth_cst WHERE dev_id="+developer+" AND emp_se_cd='FT' AND pstn_cd='TL'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.upld WHERE upld_id="+updated.upload_id()+" AND obj_ym='2026-10' AND ovwr_yn='Y'")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='cst.mth_cst' AND obj_rec_id='2026-10' AND rsn_desc='급여 업로드 #"+updated.upload_id()+" · 2명'")).isEqualTo(1);
    }
    @Test void salaryApplyOpenMonthCommitsExpensesAndOneMonthlyAuditClosedMonthPreservesAll() throws Exception {
        var people=service.apply(validate("B.8",List.of(developer("급여 대상"))).upload_id());
        long devId=people.developer_ids().get(0);
        var salary=new LinkedHashMap<>(Map.of("관리번호",com.sicost.common.validation.DeveloperManagementNumbers.allocated(devId),
            "이름","급여 대상","월 급여","4000000","4대보험(회사)","420000","팀비","50000"));
        long id=validate("B.1",List.of(salary)).upload_id();
        mvc.perform(post("/api/uploads/{uploadId}/apply",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applied_count").value(1)).andExpect(jsonPath("$.data.month").value("2026-10"));
        assertThat(scalar("SELECT count(*) FROM cst.drct_expns WHERE dev_id="+devId+" AND prj_id IS NULL AND expns_item_cd='TEAM' AND expns_amt=50000 AND upld_id="+id)).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM cst.mth_cst WHERE dev_id="+devId+" AND cst_ym='2026-10' AND indv_lbr_cst_amt=4970000 AND upld_id="+id)).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='cst.mth_cst' AND obj_rec_id='2026-10' AND data_chg_ty_cd='INSERT'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='UPLOAD'")).isEqualTo(2);
        long blocked=validate("B.1",List.of(salary)).upload_id();
        execute("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");
        String before=businessSnapshot();
        mvc.perform(post("/api/uploads/{uploadId}/apply",blocked)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        assertThat(businessSnapshot()).isEqualTo(before);
    }
    @Test void businessFailureRollsBackEveryTable()throws Exception {
        var review=validate("B.8",List.of(developer("첫째"),developer("둘째")));long before=scalar("SELECT count(*) FROM mst.dev");execute("ALTER TABLE mst.dev ADD CONSTRAINT reject_second CHECK(dev_nm != '둘째') NOT VALID");assertThatThrownBy(()->service.apply(review.upload_id())).isInstanceOf(RuntimeException.class);assertThat(scalar("SELECT count(*) FROM mst.dev")).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.upld")).isZero();assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isZero();assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isZero();
    }
    @Test void salaryParseErrorAndMonthClosingCannotReplaceExistingRows()throws Exception {
        var row=developer("급여 대상");var people=service.apply(validate("B.8",List.of(row)).upload_id());long developer=people.developer_ids().get(0);var salary=new LinkedHashMap<>(Map.of("관리번호",com.sicost.common.validation.DeveloperManagementNumbers.allocated(developer),"이름","급여 대상","월 급여","4000000","4대보험(회사)","420000"));service.apply(validate("B.1",List.of(salary)).upload_id());salary.put("월 급여","");var invalid=validate("B.1",List.of(salary));assertThatThrownBy(()->service.apply(invalid.upload_id())).isInstanceOf(ApiException.class);assertThat(scalar("SELECT slry_amt FROM cst.mth_cst WHERE dev_id="+developer)).isEqualTo(4000000);salary.put("월 급여","5000000");var valid=validate("B.1",List.of(salary));execute("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");assertThatThrownBy(()->service.apply(valid.upload_id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));assertThat(scalar("SELECT slry_amt FROM cst.mth_cst WHERE dev_id="+developer)).isEqualTo(4000000);
    }
}
