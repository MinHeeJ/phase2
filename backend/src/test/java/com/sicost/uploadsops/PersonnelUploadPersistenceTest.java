package com.sicost.uploadsops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
    }
    @AfterEach void cleanup()throws Exception{if(source==null)return;try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
    long scalar(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    void execute(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    PersonnelUploadModels.Result validate(String form,List<Map<String,String>> rows)throws Exception{return upload.validatePersonnel(form,form.equals("B.1")?"2026-10":null,new MockMultipartFile("file","업로드.xlsx","application/octet-stream",PersonnelUploadApiTest.workbook(form,rows)));}
    Map<String,String> developer(String name){return new LinkedHashMap<>(Map.of("이름",name,"직원구분","FT","주 직무","BE","직위","TL"));}
    @Test void rowOrderedImportAndAuditRoundTrip()throws Exception {
        long before=scalar("SELECT count(*) FROM mst.dev");var review=validate("B.8",List.of(developer("첫째"),developer("둘째"),developer("셋째")));assertThat(review.error_count()).isZero();assertThat(scalar("SELECT count(*) FROM mst.dev")).isEqualTo(before);
        var applied=service.apply(review.upload_id());assertThat(scalar("SELECT count(*) FROM mst.dev WHERE upld_id="+review.upload_id())).isEqualTo(3);
        try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT dev_id,mng_no,dev_nm FROM mst.dev WHERE upld_id="+review.upload_id()+" ORDER BY dev_id")){for(String name:List.of("첫째","둘째","셋째")){assertThat(rows.next()).isTrue();assertThat(rows.getString("dev_nm")).isEqualTo(name);assertThat(rows.getString("mng_no")).isEqualTo(com.sicost.common.validation.DeveloperManagementNumbers.allocated(rows.getLong("dev_id")));}}
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.dev' AND rsn_desc='엑셀 업로드 #"+review.upload_id()+"'")).isEqualTo(3);assertThat(service.apply(review.upload_id())).isEqualTo(applied);
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
    @Test void businessFailureRollsBackEveryTable()throws Exception {
        var review=validate("B.8",List.of(developer("첫째"),developer("둘째")));long before=scalar("SELECT count(*) FROM mst.dev");execute("ALTER TABLE mst.dev ADD CONSTRAINT reject_second CHECK(dev_nm != '둘째') NOT VALID");assertThatThrownBy(()->service.apply(review.upload_id())).isInstanceOf(RuntimeException.class);assertThat(scalar("SELECT count(*) FROM mst.dev")).isEqualTo(before);assertThat(scalar("SELECT count(*) FROM sys.upld")).isZero();assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isZero();assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isZero();
    }
    @Test void salaryParseErrorAndMonthClosingCannotReplaceExistingRows()throws Exception {
        var row=developer("급여 대상");var people=service.apply(validate("B.8",List.of(row)).upload_id());long developer=people.developer_ids().get(0);var salary=new LinkedHashMap<>(Map.of("관리번호",com.sicost.common.validation.DeveloperManagementNumbers.allocated(developer),"이름","급여 대상","월 급여","4000000","4대보험(회사)","420000"));service.apply(validate("B.1",List.of(salary)).upload_id());salary.put("월 급여","");var invalid=validate("B.1",List.of(salary));assertThatThrownBy(()->service.apply(invalid.upload_id())).isInstanceOf(ApiException.class);assertThat(scalar("SELECT slry_amt FROM cst.mth_cst WHERE dev_id="+developer)).isEqualTo(4000000);salary.put("월 급여","5000000");var valid=validate("B.1",List.of(salary));execute("UPDATE fin.mth_cls SET cls_stts_cd='CLOSED' WHERE cls_ym='2026-10'");assertThatThrownBy(()->service.apply(valid.upload_id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));assertThat(scalar("SELECT slry_amt FROM cst.mth_cst WHERE dev_id="+developer)).isEqualTo(4000000);
    }
}
