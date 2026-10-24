package com.sicost.uploadsops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.mybatis.spring.*;

class UploadPersistenceTest {
    String adminUrl,user,password,database;DriverManagerDataSource source;UploadService service;UploadMapper mapper;
    @TempDir Path directory;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/uploads/UploadMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(UploadMapper.class);
        service=new UploadService(mapper,new CurrentUser(),Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")),new UploadWorkbookWriter(),new PartnerUploadForm(mapper),new DataSourceTransactionManager(source));ReflectionTestUtils.setField(service,"directory",directory.toString());
    }
    @AfterEach void removeIsolatedDatabase()throws Exception{if(source==null)return;try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
    long scalar(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    void execute(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String token(String[]...rows)throws Exception{return service.validate("B.7",new MockMultipartFile("file","거래처.xlsx","application/octet-stream",UploadApiTest.file(rows))).token();}
    Map<String,Long> counts()throws Exception{var counts=new LinkedHashMap<String,Long>();for(String table:List.of("mst.ptnr","sys.upld","sys.upld_err","sys.data_chg_hist","sys.acs_hist"))counts.put(table,scalar("SELECT count(*) FROM "+table));return counts;}
    @Test void successfulImportAndReplayPersistAllRowsInOrderAndSafeHistory()throws Exception{
        String[][] rows=new String[5][];for(int i=0;i<5;i++){rows[i]=UploadApiTest.customer("123456789"+i);if(i<3)rows[i][8]="무시한 은행";}
        long original=scalar("SELECT count(*) FROM mst.ptnr");String token=token(rows);assertThat(scalar("SELECT count(*) FROM sys.upld")).isZero();var result=service.apply(token);
        assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(original+5);assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE upld_id="+result.upldId()+" AND use_yn='Y'")).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM sys.upld_err WHERE upld_id="+result.upldId()+" AND err_svrt_cd='WARN'")).isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='INSERT' AND rsn_desc='엑셀 업로드 #"+result.upldId()+"' AND chg_user_id IS NULL")).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='UPLOAD' AND scrn_id='UI-15' AND user_id IS NULL")).isEqualTo(1);
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT ptnr_no FROM mst.ptnr WHERE upld_id="+result.upldId()+" ORDER BY ptnr_id")){for(int i=1;i<=5;i++){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("C-"+String.format("%03d",i));}}
        var before=counts();assertThat(service.apply(token)).isEqualTo(result);assertThat(counts()).isEqualTo(before);
    }
    @Test void secondBusinessInsertFailureRollsBackUploadWarningsAuditAndAccess()throws Exception{
        String[] first=UploadApiTest.customer("1234567890");first[8]="무시";String token=token(first,UploadApiTest.customer("2222222222"));Map<String,Long> before=counts();
        execute("ALTER TABLE mst.ptnr ADD CONSTRAINT reject_second_import CHECK (biz_reg_no != '222-22-22222') NOT VALID");assertThatThrownBy(()->service.apply(token)).isInstanceOf(RuntimeException.class);assertThat(counts()).isEqualTo(before);
        execute("ALTER TABLE mst.ptnr DROP CONSTRAINT reject_second_import");assertThat(service.apply(token).appliedRows()).isEqualTo(2);
    }
    @Test void staleKeyRollsBackWholeFile()throws Exception{String token=token(UploadApiTest.customer("1234567890"),UploadApiTest.customer("2222222222"));execute("INSERT INTO mst.ptnr(ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no,use_yn) VALUES('EXISTING','CUST','기등록','123-45-67890','N')");var before=counts();assertThatThrownBy(()->service.apply(token)).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(409);assertThat(e.code()).isEqualTo("STALE_VALIDATION");});assertThat(counts()).isEqualTo(before);}
    @Test void concurrentTokensWithSameKeyHaveOneCommitAndAtomicConflict()throws Exception{
        UploadMapper observed=org.mockito.Mockito.spy(mapper);
        service=new UploadService(observed,new CurrentUser(),Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")),new UploadWorkbookWriter(),new PartnerUploadForm(observed),new DataSourceTransactionManager(source));ReflectionTestUtils.setField(service,"directory",directory.toString());
        String a=token(UploadApiTest.customer("1234567890")),b=token(UploadApiTest.customer("1234567890"));CyclicBarrier start=new CyclicBarrier(2),checked=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        org.mockito.Mockito.doAnswer(invocation->{String value=mapper.duplicateName(invocation.getArgument(0));checked.await(10,TimeUnit.SECONDS);return value;}).when(observed).duplicateName(org.mockito.ArgumentMatchers.anyString());
        try {
            List<Future<String>> futures=new ArrayList<>();for(String token:List.of(a,b))futures.add(pool.submit(()->{start.await(10,TimeUnit.SECONDS);try{return service.apply(token).upldId();}catch(ApiException e){assertThat(e.status()).isEqualTo(409);return e.code();}}));
            List<String> results=new ArrayList<>();for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));assertThat(results).contains("STALE_VALIDATION");assertThat(results.stream().filter(s->!s.equals("STALE_VALIDATION"))).hasSize(1);
            assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.upld WHERE upld_stts_cd='APPLIED'")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.acs_hist")).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void errorDownloadReadbackRecordsExactlyOneAccessAndCorrectedFileCanApply()throws Exception {
        var rejected=service.validate("B.7",new MockMultipartFile("file","거래처.xlsx","application/octet-stream",UploadApiTest.file(UploadApiTest.customer("bad"))));
        long id=Long.parseLong(rejected.upldId());var stored=mapper.source(id);byte[] original=java.nio.file.Files.readAllBytes(Path.of(stored.path()));
        var before=counts();var originalIssues=mapper.issues(id);var downloaded=service.errorFile(id);
        assertThat(downloaded.fileName()).isEqualTo("거래처_오류.xlsx");
        var after=counts();for(String table:before.keySet())assertThat(after.get(table)).isEqualTo(before.get(table)+(table.equals("sys.acs_hist")?1:0));
        assertThat(mapper.issues(id)).isEqualTo(originalIssues);assertThat(mapper.source(id)).isEqualTo(stored);
        assertThat(java.nio.file.Files.readAllBytes(Path.of(stored.path()))).isEqualTo(original);
        try(var c=source.getConnection();var query=c.prepareStatement("SELECT acs_ty_cd,scrn_id,obj_desc,dtl_desc,user_id FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD'" );var rows=query.executeQuery()) {
            assertThat(rows.next()).isTrue();assertThat(rows.getString("scrn_id")).isEqualTo("UI-15");assertThat(rows.getString("obj_desc")).isEqualTo(downloaded.fileName());assertThat(rows.getObject("user_id")).isNull();
            var detail=new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getString("dtl_desc"));assertThat(detail.get("upldId").asText()).isEqualTo(rejected.upldId());assertThat(detail.get("fileName").asText()).isEqualTo(downloaded.fileName());assertThat(detail.size()).isEqualTo(2);assertThat(rows.next()).isFalse();
        }
        var unchanged=service.validate("B.7",new MockMultipartFile("file",downloaded.fileName(),"application/octet-stream",downloaded.bytes()));
        assertThat(unchanged.items()).isEqualTo(rejected.items());assertThat(unchanged.errorRows()).isEqualTo(rejected.errorRows());
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(downloaded.bytes()));var out=new java.io.ByteArrayOutputStream()) {
            book.getSheetAt(0).getRow(4).getCell(2).setCellValue("1234567890");book.write(out);
            var corrected=service.validate("B.7",new MockMultipartFile("file",downloaded.fileName(),"application/octet-stream",out.toByteArray()));
            assertThat(corrected.errorRows()).isZero();assertThat(corrected.token()).isNotNull();var applied=service.apply(corrected.token());
            assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE upld_id="+applied.upldId()+" AND biz_reg_no='123-45-67890'")).isEqualTo(1);
        }
        assertThat(mapper.issues(id)).isEqualTo(originalIssues);assertThat(java.nio.file.Files.readAllBytes(Path.of(stored.path()))).isEqualTo(original);
    }
    @Test void rejectionRecheckAndErrorWorkbookReadbackUseDatabaseCodesWithoutBusinessWrites()throws Exception{
        var row=UploadApiTest.customer("1234567890");row[4]="핀테크";var rejected=service.validate("B.7",new MockMultipartFile("file","거래처.xlsx","application/octet-stream",UploadApiTest.file(row)));
        assertThat(rejected.errorRows()).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM sys.upld_err")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isZero();
        assertThat(service.errorFile(Long.parseLong(rejected.upldId())).bytes()).isNotEmpty();
        execute("INSERT INTO cmn.excel_map(excel_lbl_nm,trns_cd_grp_id,trns_cd,map_role_cd) VALUES('핀테크','INDUSTRY','SI','MAIN')");var checked=service.recheck(Long.parseLong(rejected.upldId()));assertThat(checked.errorRows()).isZero();assertThat(checked.warningRows()).isEqualTo(1);assertThat(checked.token()).isNotNull();assertThat(scalar("SELECT count(*) FROM sys.upld")).isEqualTo(1);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isZero();
        service.template("B.7");assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-15'")).isEqualTo(2);
    }
    @Test void formattedIdentifiersAreReadBackExactlyAfterApply()throws Exception {
        byte[] bytes=UploadApiTest.file(UploadApiTest.vendor("1234567890"));
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));var output=new java.io.ByteArrayOutputStream()) {
            var row=book.getSheetAt(0).getRow(4);row.getCell(6).setCellValue(1012345678d);row.getCell(9).setCellValue(12345678d);
            var phone=book.createCellStyle();phone.setDataFormat(book.createDataFormat().getFormat("000-0000-0000"));row.getCell(6).setCellStyle(phone);
            var account=book.createCellStyle();account.setDataFormat(book.createDataFormat().getFormat("000-00000000"));row.getCell(9).setCellStyle(account);
            book.write(output);bytes=output.toByteArray();
        }
        var checked=service.validate("B.7",new MockMultipartFile("file","거래처.xlsx","application/octet-stream",bytes));assertThat(checked.errorRows()).isZero();
        var applied=service.apply(checked.token());
        try(var connection=source.getConnection();var query=connection.prepareStatement("SELECT biz_reg_no,chrg_tel_no,acnt_no,use_yn,upld_id FROM mst.ptnr WHERE upld_id=?")) {
            query.setLong(1,Long.parseLong(applied.upldId()));try(var result=query.executeQuery()) {
                assertThat(result.next()).isTrue();assertThat(result.getString("biz_reg_no")).isEqualTo("123-45-67890");assertThat(result.getString("chrg_tel_no")).isEqualTo("010-1234-5678");assertThat(result.getString("acnt_no")).isEqualTo("000-12345678");assertThat(result.getString("use_yn")).isEqualTo("Y");assertThat(result.next()).isFalse();
            }
        }
    }
    @Test void repeatedRejectedRecheckIsAppendOnlyAndSurvivesServiceRestart()throws Exception {
        var row=UploadApiTest.customer("bad");row[8]="무시 은행";
        var first=service.validate("B.7",new MockMultipartFile("file","거래처.xlsx","application/octet-stream",UploadApiTest.file(row)));
        long id=Long.parseLong(first.upldId());var original=mapper.source(id);var issues=mapper.issues(id);
        service=new UploadService(mapper,new CurrentUser(),Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")),new UploadWorkbookWriter(),new PartnerUploadForm(mapper),new DataSourceTransactionManager(source));ReflectionTestUtils.setField(service,"directory",directory.toString());
        var second=service.recheck(id);assertThat(second.upldId()).isNotEqualTo(first.upldId());assertThat(second.token()).isNull();
        assertThat(mapper.source(id)).isEqualTo(original);assertThat(mapper.issues(id)).isEqualTo(issues);assertThat(mapper.source(Long.parseLong(second.upldId()))).isEqualTo(original);
        assertThat(scalar("SELECT count(*) FROM sys.upld WHERE upld_stts_cd='REJECTED'")).isEqualTo(2);assertThat(scalar("SELECT count(*) FROM sys.upld_err")).isEqualTo(4);assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isZero();assertThat(Path.of(original.path())).exists();
    }
    @Test void templateUsesOnlyActiveDatabaseCodeNamesAndRecordsEveryDownload()throws Exception {
        var bank=mapper.codes().stream().filter(c->c.group().equals("BANK")).findFirst().orElseThrow();
        try(var connection=source.getConnection();var update=connection.prepareStatement("UPDATE cmn.cd_dtl SET use_yn='N' WHERE cd_grp_id='BANK' AND dtl_cd=?")) {
            update.setString(1,bank.code());assertThat(update.executeUpdate()).isEqualTo(1);
        }
        var expected=mapper.codes();var before=counts();
        for(int request=0;request<2;request++) {
            var file=service.template("B.7");assertThat(file.fileName()).isEqualTo("거래처_업로드양식.xlsx");
            try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file.bytes()))) {
                var lists=book.getSheet("목록");assertThat(lists.getProtect()).isTrue();
                var groups=List.of("PARTNER_KIND","INDUSTRY","BANK");
                for(int column=0;column<groups.size();column++) {
                    String group=groups.get(column);var names=new ArrayList<String>();
                    for(int row=1;row<=lists.getLastRowNum();row++) {
                        var cell=lists.getRow(row).getCell(column);if(cell!=null)names.add(cell.getStringCellValue());
                    }
                    assertThat(names).containsExactlyElementsOf(expected.stream().filter(c->c.group().equals(group)).map(c->c.name()).toList());
                }
            }
        }
        assertThat(scalar("SELECT count(*) FROM sys.acs_hist WHERE acs_ty_cd='DOWNLOAD' AND scrn_id='UI-15' AND obj_desc='거래처_업로드양식.xlsx' AND user_id IS NULL AND dtl_desc::jsonb->>'form'='B.7' AND dtl_desc::jsonb->>'fileName'='거래처_업로드양식.xlsx'")).isEqualTo(2);
        for(String table:List.of("mst.ptnr","sys.upld","sys.upld_err","sys.data_chg_hist"))assertThat(scalar("SELECT count(*) FROM "+table)).isEqualTo(before.get(table));
    }
    @Test void downloadedExampleIsAbsentFromRejectedRecheckedAndCommittedDatabaseRows()throws Exception {
        byte[] bytes=service.template("B.7").bytes();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));var out=new java.io.ByteArrayOutputStream()) {
            var sheet=book.getSheetAt(0);sheet.getRow(4).getCell(0).setCellValue("미등록 구분");sheet.getRow(4).getCell(2).setCellValue("wrong");
            String[] actual=UploadApiTest.customer("1234567890");actual[4]="핀테크";
            var row=sheet.createRow(5);for(int i=0;i<actual.length;i++)row.createCell(i).setCellValue(actual[i]);
            book.write(out);bytes=out.toByteArray();
        }
        var rejected=service.validate("B.7",new MockMultipartFile("file","거래처.xlsx","application/octet-stream",bytes));
        assertThat(rejected.totalRows()).isEqualTo(1);assertThat(rejected.errorRows()).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.upld_err WHERE row_no=5")).isZero();
        assertThat(scalar("SELECT count(*) FROM sys.upld_err WHERE row_no=6")).isEqualTo(1);
        execute("INSERT INTO cmn.excel_map(excel_lbl_nm,trns_cd_grp_id,trns_cd,map_role_cd) VALUES('핀테크','INDUSTRY','SI','MAIN')");
        var checked=service.recheck(Long.parseLong(rejected.upldId()));assertThat(checked.totalRows()).isEqualTo(1);assertThat(checked.errorRows()).isZero();
        assertThat(scalar("SELECT count(*) FROM mst.ptnr")).isZero();
        var applied=service.apply(checked.token());assertThat(applied.appliedRows()).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE upld_id="+applied.upldId()+" AND ptnr_nm='테스트 거래처'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM mst.ptnr WHERE ptnr_nm LIKE '예시)%'")).isZero();
        assertThat(scalar("SELECT count(*) FROM sys.upld WHERE upld_stts_cd='REJECTED' AND tot_row_cnt=1 AND err_row_cnt=1")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.upld WHERE upld_stts_cd='APPLIED' AND tot_row_cnt=1 AND nrml_row_cnt=1")).isEqualTo(1);
    }
}
