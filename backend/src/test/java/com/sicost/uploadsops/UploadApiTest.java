package com.sicost.uploadsops;

import static com.sicost.uploadsops.UploadModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@WebMvcTest(controllers=UploadController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,UploadService.class,CurrentUser.class,PermissionService.class,UploadApiTest.Time.class})
class UploadApiTest {
    static class MutableClock extends Clock {
        Instant instant=Instant.parse("2026-10-22T00:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return instant;}
    }
    @TestConfiguration static class Time {
        @Bean MutableClock clock(){return new MutableClock();}
        @Bean UploadStorage storage()throws IOException{return new UploadStorage(Files.createTempDirectory("upload-test-").toString());}
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @MockBean UploadMapper mapper;
    @MockBean PlatformTransactionManager manager;
    AtomicLong ids;
    Map<Long,Original> originals;
    Map<Long,List<StoredIssue>> saved;
    List<Code> codes=List.of(new Code("PARTNER_KIND","CUST","고객사",null),new Code("PARTNER_KIND","VEND","협력사",null),new Code("PARTNER_KIND","BOTH","둘 다",null),new Code("INDUSTRY","SI","SI",null),new Code("BANK","KB","국민은행",null));
    @BeforeEach void setup(){
        clock.instant=Instant.parse("2026-10-22T00:00:00Z");ids=new AtomicLong();originals=new HashMap<>();saved=new HashMap<>();
        when(manager.getTransaction(any())).thenAnswer(a->new SimpleTransactionStatus());
        when(mapper.codes()).thenReturn(codes);when(mapper.mappings()).thenReturn(List.of(new Code("PARTNER_KIND","VEND","협력사","협력업체")));
        when(mapper.nextPartnerId()).thenAnswer(a->ids.incrementAndGet());
        when(mapper.insertLedger(any())).thenAnswer(a->{Ledger w=a.getArgument(0);Long id=ids.incrementAndGet();originals.put(id,new Original(w.fileName(),w.path()));return id;});
        when(mapper.original(anyLong())).thenAnswer(a->originals.get(a.getArgument(0)));
        when(mapper.issues(anyLong())).thenAnswer(a->saved.getOrDefault(a.getArgument(0),List.of()));
        doAnswer(a->{IssueWrite w=a.getArgument(0);Issue i=w.issue();saved.computeIfAbsent(w.id(),k->new ArrayList<>()).add(new StoredIssue(i.severity(),i.rowNo(),i.column(),i.rawValue(),i.message()));return null;}).when(mapper).insertIssue(any());
    }
    String[] customer(String number){return new String[]{"고객사","거래처",number,"","","","","tax@company.co.kr","","","",""};}
    String[] vendor(String number){return new String[]{"협력사","협력 거래처",number,"","","","","","국민은행","1234-5678","예금주",""};}
    byte[] workbook(String[] headers,List<String[]> rows,int header)throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("입력");var h=sheet.createRow(header);for(int c=0;c<headers.length;c++)h.createCell(c).setCellValue(headers[c]);
            int r=header+1;for(String[] data:rows){var row=sheet.createRow(r++);for(int c=0;c<data.length;c++)row.createCell(c).setCellValue(data[c]);}
            book.write(out);return out.toByteArray();
        }
    }
    byte[] workbook(List<String[]> rows)throws Exception{return workbook(UploadForm.TITLES,rows,3);}
    ResultActions validate(byte[] bytes,String name)throws Exception{return mvc.perform(multipart("/api/uploads/B.7/validate").file(new MockMultipartFile("file",name,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)));}
    JsonNode data(byte[] bytes)throws Exception{return json.readTree(validate(bytes,"거래처.xlsx").andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).get("data");}
    String token(List<String[]> rows)throws Exception{return data(workbook(rows)).get("token").asText();}
    @Test @DisplayName("auth happy business side-effect: template DOWNLOAD lists database codes and records CurrentUser JSON access")
    void publicTemplateIsRealThreeSheetWorkbookAndAudited()throws Exception {
        var response=mvc.perform(get("/api/uploads/templates/B.7")).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition")).contains("filename*=UTF-8''");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))){assertThat(book.getNumberOfSheets()).isEqualTo(3);assertThat(book.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("구분*");assertThat(book.getSheetAt(0).getRow(4).getCell(1).getStringCellValue()).startsWith("예시)");assertThat(book.getSheetAt(2).getProtect()).isTrue();assertThat(book.getSheetAt(0).getDataValidations()).hasSize(3);assertThat(book.getSheetAt(2).getRow(1).getCell(2).getStringCellValue()).isEqualTo("국민은행");assertThat(book.getSheetAt(0).getColumnStyle(2).getDataFormatString()).isEqualTo("@");}
        verify(mapper).access(argThat(a->a.type().equals("DOWNLOAD")&&a.fileName().equals("거래처_업로드양식.xlsx")&&a.detail().equals("{\"form\":\"B.7\"}")&&a.actor()==null));verify(mapper,never()).insertPartner(any());
    }
    @Test void unknownTemplateIs404()throws Exception {mvc.perform(get("/api/uploads/templates/B.1")).andExpect(status().isNotFound());verifyNoInteractions(mapper);}
    @Test void rejectedSixRowsCountRowsNotMessagesAndDoNotChangeBusiness()throws Exception {
        List<String[]> rows=new ArrayList<>();rows.add(customer("1234567890"));for(int n=1;n<=5;n++){var row=customer("223456789"+n);row[1]="";row[7]="";rows.add(row);}
        var result=data(workbook(rows));assertThat(result.get("totalRows").asInt()).isEqualTo(6);assertThat(result.get("okRows").asInt()).isEqualTo(1);assertThat(result.get("errorRows").asInt()).isEqualTo(5);assertThat(result.has("token")).isFalse();assertThat(result.toString()).doesNotContain("path","sicost-uploads");
        verify(mapper).insertLedger(argThat(w->w.status().equals("REJECTED")));verify(mapper,times(10)).insertIssue(any());verify(mapper,never()).insertPartner(any());verify(mapper,never()).history(any());verify(mapper,never()).access(any());
    }
    @Test @DisplayName("auth happy business side-effect: row ERROR and WARN persist REJECTED upldId without token or business writes")
    void validationHttpContractPreservesRejectedCountsAndEveryIssue()throws Exception {
        var row=customer("bad");row[8]="무시할 은행";
        mvc.perform(multipart("/api/uploads/B.7/validate").file(new MockMultipartFile("file","거래처.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook(Collections.singletonList(row)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.fileName").value("거래처.xlsx"))
            .andExpect(jsonPath("$.data.totalRows").value(1)).andExpect(jsonPath("$.data.okRows").value(0))
            .andExpect(jsonPath("$.data.errorRows").value(1)).andExpect(jsonPath("$.data.warningRows").value(1))
            .andExpect(jsonPath("$.data.upldId").value("1")).andExpect(jsonPath("$.data.token").doesNotExist())
            .andExpect(jsonPath("$.data.path").doesNotExist()).andExpect(jsonPath("$.data.items.length()").value(2));
        verify(mapper).insertLedger(argThat(w->w.status().equals("REJECTED")&&w.actor()==null));
        verify(mapper).insertIssue(argThat(w->w.id()==1L&&w.issue().severity().equals("ERROR")));
        verify(mapper).insertIssue(argThat(w->w.id()==1L&&w.issue().severity().equals("WARN")));
        verify(mapper,never()).insertPartner(any());verify(mapper,never()).history(any());verify(mapper,never()).access(any());
    }
    @Test @DisplayName("auth happy: successful multipart validation returns UUID token with no durable ledger")
    void successfulValidationHttpContractIsTransient()throws Exception {
        var response=mvc.perform(multipart("/api/uploads/B.7/validate").file(new MockMultipartFile("file","거래처.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook(Collections.singletonList(customer("1234567890"))))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.okRows").value(1))
            .andExpect(jsonPath("$.data.errorRows").value(0)).andExpect(jsonPath("$.data.token").isString())
            .andExpect(jsonPath("$.data.upldId").doesNotExist()).andReturn().getResponse();
        assertThat(UUID.fromString(json.readTree(response.getContentAsByteArray()).path("data").path("token").asText())).isNotNull();
        verify(mapper,never()).insertLedger(any());verify(mapper,never()).insertPartner(any());
    }
    @Test @DisplayName("auth happy business side-effect: APPLIED upld, ptnr, WARN and data_chg_hist plus UPLOAD acs_hist")
    void fiveRowsThreeWarningsReturnTokenThenApplyAllInOrder()throws Exception {
        List<String[]> rows=new ArrayList<>();for(int n=0;n<5;n++){var row=vendor("323456789"+n);if(n<3)row[0]="협력업체";rows.add(row);}
        var result=data(workbook(rows));assertThat(result.get("warningRows").asInt()).isEqualTo(3);assertThat(result.get("okRows").asInt()).isEqualTo(5);assertThat(result.has("upldId")).isFalse();verify(mapper,never()).insertLedger(any());
        String token=result.get("token").asText();UUID.fromString(token);
        mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isOk()).andExpect(jsonPath("$.data.appliedRows").value(5)).andExpect(jsonPath("$.data.upldId").isString());
        verify(mapper,times(5)).insertPartner(any());verify(mapper,times(5)).history(argThat(h->h.actor()==null));verify(mapper,times(3)).insertIssue(any());verify(mapper).access(argThat(a->a.type().equals("UPLOAD")));
        mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VALIDATION"));
    }
    @ParameterizedTest @CsvSource({"123,사업자번호","'',상호"})
    void invalidBusinessFieldsAreRowErrors(String value,String column)throws Exception {var row=customer("1234567890");row[column.equals("상호")?1:2]=value;var result=data(workbook(Collections.singletonList(row)));assertThat(result.get("errorRows").asInt()).isEqualTo(1);assertThat(result.get("items").get(0).get("column").asText()).isEqualTo(column);}
    @ParameterizedTest @ValueSource(ints={1,3,5,6,10,11})
    void lengthsAreEnforced(int column)throws Exception {var row=vendor("1234567890");int max=column==1?50:column==10?30:column==11?500:20;row[column]="가".repeat(max+1);assertThat(data(workbook(Collections.singletonList(row))).get("errorRows").asInt()).isEqualTo(1);}
    @Test void customerEmailRequired()throws Exception {var row=customer("1234567890");row[7]="";assertThat(data(workbook(Collections.singletonList(row))).get("items").get(0).get("message").asText()).isEqualTo("고객사는 세금계산서 이메일이 꼭 필요합니다.");}
    @Test void malformedEmailRejected()throws Exception {var row=customer("1234567890");row[7]="not-email";assertThat(data(workbook(Collections.singletonList(row))).get("errorRows").asInt()).isEqualTo(1);}
    @ParameterizedTest @ValueSource(ints={8,9,10})
    void vendorPaymentFieldsRequired(int column)throws Exception {var row=vendor("1234567890");row[column]="";assertThat(data(workbook(Collections.singletonList(row))).get("errorRows").asInt()).isEqualTo(1);}
    @Test void malformedAccountRejected()throws Exception {var row=vendor("1234567890");row[9]="not-account";assertThat(data(workbook(Collections.singletonList(row))).get("errorRows").asInt()).isEqualTo(1);}
    @Test void irrelevantFieldsAreIgnoredWithoutFormatErrors()throws Exception {
        var c=customer("1234567890");c[8]="없는은행";c[9]="bad";c[10]="가".repeat(100);var v=vendor("2234567890");v[7]="bad";
        var result=data(workbook(List.of(c,v)));assertThat(result.get("errorRows").asInt()).isZero();assertThat(result.get("warningRows").asInt()).isEqualTo(2);
        mvc.perform(post("/api/uploads/validated/"+result.get("token").asText()+"/apply")).andExpect(status().isOk());verify(mapper).insertPartner(argThat(w->w.row().kind().equals("CUST")&&w.row().bank()==null&&w.row().account()==null&&w.row().holder()==null));verify(mapper).insertPartner(argThat(w->w.row().kind().equals("VEND")&&w.row().email()==null));
    }
    @Test void exampleAndEmptyRowsAreSkippedPhysicalRowsRetained()throws Exception {var example=customer("bad");example[1]="예시)";var blank=new String[12];Arrays.fill(blank,"");var result=data(workbook(List.of(example,blank,customer("bad"))));assertThat(result.get("totalRows").asInt()).isEqualTo(1);assertThat(result.get("items").get(0).get("rowNo").asInt()).isEqualTo(7);}
    @Test void duplicateSecondRowAndExistingInactiveKeyAreDifferentErrors()throws Exception {
        when(mapper.existing("223-45-67890")).thenReturn("중지 거래처");var result=data(workbook(List.of(customer("1234567890"),customer("1234567890"),customer("2234567890"))));assertThat(result.get("errorRows").asInt()).isEqualTo(2);assertThat(result.toString()).contains("파일 안에서 5행","중지 거래처");
    }
    @Test void reorderedAliasesNumericAndCachedFormulaAreRead()throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {var s=book.createSheet();var h=s.createRow(2);String[] headers={" 회사명 * ","사업자등록번호","거래처 구분","계산서 이메일"};for(int c=0;c<4;c++)h.createCell(c).setCellValue(headers[c]);var r=s.createRow(3);r.createCell(0).setCellValue("회사");r.createCell(1).setCellValue(1234567890d);r.createCell(2).setCellValue("고객사");r.createCell(3).setCellFormula("\"tax@company.co.kr\"");book.getCreationHelper().createFormulaEvaluator().evaluateAll();book.write(out);var result=data(out.toByteArray());assertThat(result.get("errorRows").asInt()).isZero();mvc.perform(post("/api/uploads/validated/"+result.get("token").asText()+"/apply")).andExpect(status().isOk());verify(mapper).insertPartner(argThat(w->w.row().bizNo().equals("123-45-67890")&&w.row().rowNo()==4));}
    }
    @ParameterizedTest @ValueSource(strings={"xls","fake","header","required","empty","duplicate-header","too-many"})
    void fileErrorsRejectWholeFile(String scenario)throws Exception {
        byte[] bytes;String name="검사.xlsx",code;
        switch(scenario) {
            case "xls" -> {bytes=new byte[]{1};name="검사.xls";code="UNSUPPORTED_FILE_TYPE";}
            case "fake" -> {bytes="not zip".getBytes();code="UNSUPPORTED_FILE_TYPE";}
            case "header" -> {bytes=workbook(new String[]{"제목"},Collections.singletonList(new String[]{"값"}),0);code="HEADER_NOT_FOUND";}
            case "required" -> {bytes=workbook(new String[]{"사업자번호","구분"},Collections.singletonList(new String[]{"1","고객사"}),0);code="REQUIRED_COLUMN_MISSING";}
            case "duplicate-header" -> {bytes=workbook(new String[]{"사업자번호","상호","상호","구분"},List.of(),0);code="REQUIRED_COLUMN_MISSING";}
            case "too-many" -> {List<String[]> rows=new ArrayList<>();for(int n=0;n<10001;n++)rows.add(customer(String.format("%010d",n)));bytes=workbook(rows);code="TOO_MANY_ROWS";}
            default -> {bytes=workbook(List.of());code="EMPTY_FILE";}
        }
        validate(bytes,name).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.field").value("file"));verify(mapper,never()).insertLedger(any());verify(mapper,never()).insertPartner(any());
    }
    @Test void oversizedFileIs413()throws Exception {validate(new byte[10*1024*1024+1],"검사.xlsx").andExpect(status().isPayloadTooLarge());verifyNoInteractions(mapper);}
    @Test @DisplayName("validation: POST multipart without required file returns field error before persistence")
    void missingPartIs400()throws Exception {
        mvc.perform(post("/api/uploads/B.7/validate").contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file")).andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(mapper);
    }
    @Test void malformedTokenIs400BeforeWrite()throws Exception {mvc.perform(post("/api/uploads/validated/bad/apply")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("token"));verifyNoInteractions(mapper);}
    @Test void unknownUuidIs410()throws Exception {mvc.perform(post("/api/uploads/validated/"+UUID.randomUUID()+"/apply")).andExpect(status().isGone());verifyNoInteractions(mapper);}
    @Test void exactExpiryIs410AndLeavesDatabaseUnchanged()throws Exception {String token=token(Collections.singletonList(customer("1234567890")));clock.instant=clock.instant.plusSeconds(1800);mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isGone()).andExpect(jsonPath("$.message").value("검사 후 30분이 지났습니다. 다시 올려 주세요."));verify(mapper,never()).insertLedger(any());}
    @Test void staleKeyPreventsEveryInsert()throws Exception {String token=token(List.of(customer("1234567890"),customer("2234567890")));when(mapper.existing("223-45-67890")).thenReturn("새 등록");mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VALIDATION"));verify(mapper,never()).insertLedger(any());verify(mapper,never()).insertPartner(any());}
    @Test void uniquenessRaceIsTranslatedAfterTransactionRollback()throws Exception {String token=token(Collections.singletonList(customer("1234567890")));doThrow(new org.springframework.dao.DuplicateKeyException("private sql")).when(mapper).insertPartner(any());mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VALIDATION"));verify(manager).rollback(any());verify(mapper,never()).access(any());}
    @Test void secondInsertFailureRollsBackTransactionAndDoesNotConsumeToken()throws Exception {String token=token(List.of(customer("1234567890"),customer("2234567890")));doNothing().doThrow(new IllegalStateException("failure")).doNothing().when(mapper).insertPartner(any());mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isInternalServerError());verify(manager).rollback(any());verify(mapper,never()).history(any());mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isOk());}
    @Test @DisplayName("auth happy business side-effect: REJECTED recheck preserves original and returns token after mapping repair")
    void recheckUsesStoredOriginalLatestMappingAndAppendOnlyLedgers()throws Exception {
        var row=customer("1234567890");row[4]="핀테크";var result=data(workbook(Collections.singletonList(row)));String id=result.get("upldId").asText();
        assertThat(id).isEqualTo("1");
        mvc.perform(post("/api/uploads/1/recheck")).andExpect(status().isOk()).andExpect(jsonPath("$.data.upldId").value("2")).andExpect(jsonPath("$.data.token").doesNotExist());
        when(mapper.mappings()).thenReturn(List.of(new Code("INDUSTRY","SI","SI","핀테크")));
        mvc.perform(post("/api/uploads/1/recheck")).andExpect(status().isOk()).andExpect(jsonPath("$.data.errorRows").value(0)).andExpect(jsonPath("$.data.warningRows").value(1)).andExpect(jsonPath("$.data.token").isString()).andExpect(jsonPath("$.data.upldId").doesNotExist()).andExpect(jsonPath("$.data.fileName").value("거래처.xlsx"));verify(mapper,times(2)).insertLedger(any());verify(mapper,never()).insertPartner(any());
    }
    @Test void ambiguousMappingIsErrorNotFirstTarget()throws Exception {when(mapper.mappings()).thenReturn(List.of(new Code("PARTNER_KIND","CUST","고객사","업체"),new Code("PARTNER_KIND","VEND","협력사","업체")));var row=customer("1234567890");row[0]="업체";var result=data(workbook(Collections.singletonList(row)));assertThat(result.get("items").get(0).get("fix").get("group").asText()).isEqualTo("PARTNER_KIND");}
    @ParameterizedTest @ValueSource(strings={"recheck","error-file"})
    void absentOriginalIs404(String operation)throws Exception {mvc.perform(operation.equals("recheck")?post("/api/uploads/999/recheck"):get("/api/uploads/999/error-file")).andExpect(status().isNotFound());verify(mapper,never()).insertPartner(any());}
    @Test @DisplayName("auth happy business side-effect: error-file DOWNLOAD preserves row errors until corrected reupload")
    void errorFileRetainsOrderStylesAndCanBeCorrectedAndReuploaded()throws Exception {
        var result=data(workbook(Collections.singletonList(customer("bad"))));String id=result.get("upldId").asText();
        assertThat(id).isEqualTo("1");
        byte[] bytes=mvc.perform(get("/api/uploads/1/error-file")).andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().exists("Content-Disposition")).andReturn().getResponse().getContentAsByteArray();
        assertThat(data(bytes).get("errorRows").asInt()).isEqualTo(1);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()) {assertThat(book.getNumberOfSheets()).isEqualTo(2);assertThat(book.getSheetAt(1).getSheetName()).isEqualTo("오류 목록");var sheet=book.getSheetAt(0);assertThat(sheet.getRow(3).getCell(12).getStringCellValue()).isEqualTo("오류 사유");assertThat(sheet.getRow(4).getCell(2).getCellStyle().getFillForegroundColor()).isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.ROSE.getIndex());sheet.getRow(4).getCell(2).setCellValue("1234567890");book.write(out);assertThat(data(out.toByteArray()).has("token")).isTrue();}
        verify(mapper).access(argThat(a->a.type().equals("DOWNLOAD")&&a.fileName().equals("거래처_오류.xlsx")&&a.detail().contains(id)&&a.actor()==null));verify(mapper,never()).insertPartner(any());
    }
}
