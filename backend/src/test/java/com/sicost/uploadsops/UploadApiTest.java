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
import java.util.concurrent.*;
import org.apache.poi.ss.usermodel.*;
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
@Import({RequestIdFilter.class,ApiExceptionHandler.class,UploadService.class,PartnerUploadForm.class,UploadWorkbookWriter.class,CurrentUser.class,PermissionService.class,UploadApiTest.Time.class})
class UploadApiTest {
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-22T00:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    @TestConfiguration static class Time {@Bean MutableClock clock(){return new MutableClock();}}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired UploadService service;@Autowired MutableClock clock;
    @MockBean UploadMapper mapper;@MockBean PlatformTransactionManager transactions;
    @org.junit.jupiter.api.io.TempDir Path downloadDirectory;
    static final List<UploadCode> CODES=List.of(new UploadCode("PARTNER_KIND","CUST","고객사"),new UploadCode("PARTNER_KIND","VEND","협력사"),new UploadCode("PARTNER_KIND","BOTH","둘 다"),new UploadCode("INDUSTRY","SI","SI"),new UploadCode("BANK","KB","국민은행"));
    @BeforeEach void setup(){clock.now=Instant.parse("2026-10-22T00:00:00Z");when(mapper.codes()).thenReturn(CODES);when(mapper.mappings()).thenReturn(List.of(new UploadMapping("PARTNER_KIND","협력업체","VEND")));when(mapper.insertUpload(any())).thenReturn(7L);when(mapper.nextPartnerId()).thenReturn(1L,2L,3L,4L,5L);when(transactions.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());}
    static String[] customer(String biz){return new String[]{"CUST","테스트 거래처",biz,"","SI","","","tax@company.co.kr","","","",""};}
    static String[] vendor(String biz){return new String[]{"VEND","협력",biz,"","SI","","","","KB","12345678","예금주",""};}
    static byte[] workbook(String[] headers,List<String[]> data,int headerRow)throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){Sheet sheet=book.createSheet("원본");Row h=sheet.createRow(headerRow);for(int i=0;i<headers.length;i++)h.createCell(i).setCellValue(headers[i]);for(int n=0;n<data.size();n++){Row row=sheet.createRow(headerRow+n+1);for(int c=0;c<data.get(n).length;c++)row.createCell(c).setCellValue(data.get(n)[c]);}book.write(out);return out.toByteArray();}
    }
    static byte[] file(String[]...data)throws Exception{return workbook(PartnerUploadForm.HEADERS.toArray(String[]::new),Arrays.asList(data),3);}
    ResultActions validate(byte[] bytes)throws Exception{return mvc.perform(multipart("/api/uploads/B.7/validate").file(new MockMultipartFile("file","거래처.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)));}
    JsonNode data(ResultActions response)throws Exception{return json.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");}
    String token(String[]...rows)throws Exception{return data(validate(file(rows))).get("token").asText();}
    void noBusiness(){verify(mapper,never()).insertPartner(any());verify(mapper,never()).appendHistory(any());}
    @Test void publicTemplateIsRealThreeSheetWorkbookWithTextAndValidation()throws Exception {
        var response=mvc.perform(get("/api/uploads/templates/B.7")).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition")).contains("UTF-8");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))){assertThat(book.getNumberOfSheets()).isEqualTo(3);var sheet=book.getSheetAt(0);assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("* 표시는 필수입니다. 4행 제목은 바꾸지 마세요.");assertThat((Object)sheet.getRow(1)).isNotNull();assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).contains("구분","*");assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).startsWith("예시)");assertThat(sheet.getDataValidations()).hasSize(3);assertThat(sheet.getColumnStyle(2).getDataFormatString()).isEqualTo("@");assertThat(sheet.getColumnStyle(9).getDataFormatString()).isEqualTo("@");assertThat(book.getSheet("목록").getProtect()).isTrue();assertThat(book.getSheet("목록").getRow(1).getCell(0).getStringCellValue()).isEqualTo("고객사");}
        verify(mapper).access(argThat(a->a.type().equals("DOWNLOAD")&&a.actor()==null&&a.display().equals("거래처_업로드양식.xlsx")&&a.detail().contains("B.7")));noBusiness();
    }
    @Test void unknownTemplateIs404()throws Exception{mvc.perform(get("/api/uploads/templates/other")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));verify(mapper,never()).access(any());}
    @Test void eachTemplateRequestRecordsExactlyOneDownloadWithCurrentUserAndNoBusinessWrites()throws Exception {
        for(int i=0;i<2;i++)mvc.perform(get("/api/uploads/templates/B.7")).andExpect(status().isOk());
        var accesses=org.mockito.ArgumentCaptor.forClass(UploadAccess.class);verify(mapper,times(2)).access(accesses.capture());
        for(var access:accesses.getAllValues()) {
            assertThat(access.type()).isEqualTo("DOWNLOAD");assertThat(access.actor()).isNull();
            assertThat(access.display()).isEqualTo("거래처_업로드양식.xlsx");
            assertThat(json.readTree(access.detail()).get("form").asText()).isEqualTo("B.7");
            assertThat(json.readTree(access.detail()).get("fileName").asText()).isEqualTo(access.display());
            assertThat(access.detail()).doesNotContain("path");
        }
        noBusiness();verify(mapper,never()).insertUpload(any());verify(mapper,never()).insertIssue(any());
    }
    @Test void failedTemplateGenerationDoesNotRecordSuccessfulDownload()throws Exception {
        when(mapper.codes()).thenReturn(Arrays.asList((UploadCode)null));
        mvc.perform(get("/api/uploads/templates/B.7")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.success").value(false));
        verify(mapper,never()).access(any());noBusiness();
    }
    @Test void templateDatabaseFailureReturnsErrorWithoutSuccessfulDownloadAudit()throws Exception {
        when(mapper.codes()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database details"));
        mvc.perform(get("/api/uploads/templates/B.7")).andExpect(status().isInternalServerError())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database details"))));
        verify(mapper,never()).access(any());noBusiness();
    }
    @Test void untouchedDownloadedTemplateHasNoImportableRowsOrLedger()throws Exception {
        byte[] bytes=service.template("B.7").bytes();
        validate(bytes).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_FILE"));
        verify(mapper,never()).insertUpload(any());verify(mapper,never()).insertIssue(any());noBusiness();
    }
    @Test void downloadedExampleNeverParticipatesInValidationDuplicateDetectionOrApply()throws Exception {
        byte[] bytes=service.template("B.7").bytes();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()) {
            var sheet=book.getSheetAt(0);var example=sheet.getRow(4);
            example.getCell(0).setCellValue("미등록 구분");example.getCell(2).setCellValue("1234567890");
            example.getCell(8).setCellValue("미등록 은행");example.getCell(9).setCellValue("잘못된 계좌");
            String[] values=customer("1234567890");values[6]="010-0012-0034";
            var row=sheet.createRow(5);for(int column=0;column<values.length;column++)row.createCell(column).setCellValue(values[column]);
            book.write(out);bytes=out.toByteArray();
        }
        var result=data(validate(bytes));assertThat(result.get("totalRows").asInt()).isEqualTo(1);
        assertThat(result.get("errorRows").asInt()).isZero();assertThat(result.get("warningRows").asInt()).isZero();
        mvc.perform(post("/api/uploads/validated/"+result.get("token").asText()+"/apply")).andExpect(status().isOk()).andExpect(jsonPath("$.data.appliedRows").value(1));
        verify(mapper,times(1)).insertPartner(argThat(w->w.input().ptnr_nm().equals("테스트 거래처")&&w.input().chrg_tel_no().equals("010-0012-0034")));
        verify(mapper,times(1)).appendHistory(any());verify(mapper,never()).insertIssue(any());
    }
    @Test void recheckSkipsInvalidExampleInSameSavedOriginalAndPreservesActualRowNumbers()throws Exception {
        String[] example=customer("not a business number");example[1]=" 예시) 검사 제외 ";example[0]="미등록 구분";example[4]="미등록 업종";
        String[] actual=customer("1234567890");actual[4]="핀테크";
        var initial=data(validate(file(example,actual)));assertThat(initial.get("totalRows").asInt()).isEqualTo(1);
        assertThat(initial.get("items").get(0).get("rowNo").asInt()).isEqualTo(6);
        var upload=org.mockito.ArgumentCaptor.forClass(UploadWrite.class);verify(mapper).insertUpload(upload.capture());
        var context=upload.getValue().context();when(mapper.source(7)).thenReturn(new UploadSource(context.fileName(),context.path()));
        when(mapper.mappings()).thenReturn(List.of(new UploadMapping("INDUSTRY","핀테크","SI")));
        var checked=data(mvc.perform(post("/api/uploads/7/recheck")));
        assertThat(checked.get("totalRows").asInt()).isEqualTo(1);assertThat(checked.get("errorRows").asInt()).isZero();
        assertThat(checked.get("warningRows").asInt()).isEqualTo(1);assertThat(checked.get("items").get(0).get("rowNo").asInt()).isEqualTo(6);
        verify(mapper,times(1)).insertUpload(any());noBusiness();
        mvc.perform(post("/api/uploads/validated/"+checked.get("token").asText()+"/apply")).andExpect(status().isOk()).andExpect(jsonPath("$.data.appliedRows").value(1));
        verify(mapper,times(1)).insertPartner(argThat(w->!w.input().ptnr_nm().startsWith("예시)")));
    }
    @Test void validatesSixRowsFiveErrorsAndPersistsOnlyRejectedLedger()throws Exception{
        String[][] rows=new String[6][];for(int i=0;i<6;i++)rows[i]=customer("123456789"+i);for(int i=1;i<6;i++)rows[i][1]="";
        JsonNode result=data(mvc.perform(multipart("/api/uploads/B.7/validate")
            .file(new MockMultipartFile("file","거래처.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",file(rows)))));
        assertThat(result.get("totalRows").asInt()).isEqualTo(6);assertThat(result.get("okRows").asInt()).isEqualTo(1);assertThat(result.get("errorRows").asInt()).isEqualTo(5);assertThat(result.get("upldId").asText()).isEqualTo("7");assertThat(result.has("token")).isFalse();assertThat(result.toString()).doesNotContain("file_path_addr","/sicost/uploads");
        verify(mapper).insertUpload(argThat(w->w.status().equals("REJECTED")&&Files.exists(Path.of(w.context().path()))));verify(mapper,times(5)).insertIssue(any());noBusiness();verify(mapper,never()).access(any());
    }
    @Test void cleanPublicValidationReturnsUuidWithoutUploadLedger()throws Exception{String token=token(customer("1234567890"));assertThat(UUID.fromString(token)).isNotNull();verify(mapper,never()).insertUpload(any());noBusiness();}
    @Test void appliesFiveRowsThreeWarningsAndIdempotentReplayHasNoExtraWrites()throws Exception{
        String[][] rows=new String[5][];for(int i=0;i<5;i++){rows[i]=customer("123456789"+i);if(i<3)rows[i][8]="무시 은행";}
        String token=token(rows);verify(mapper,never()).insertUpload(any());
        for(int i=0;i<2;i++)mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isOk()).andExpect(jsonPath("$.data.upldId").value("7")).andExpect(jsonPath("$.data.appliedRows").value(5));
        verify(mapper,times(1)).insertUpload(argThat(w->w.status().equals("APPLIED")&&w.result().warningRows()==3));verify(mapper,times(5)).insertPartner(any());verify(mapper).insertPartner(argThat(w->w.id()==1&&w.no().equals("C-001")&&w.uploadId()==7&&w.input().bank_cd()==null));verify(mapper,times(3)).insertIssue(any());verify(mapper,times(5)).appendHistory(argThat(w->w.uploadId()==7&&w.actor()==null));verify(mapper).access(argThat(a->a.type().equals("UPLOAD")&&!a.detail().contains("path")));verify(transactions).commit(any());
    }
    @Test void malformedTokenIs400WithoutWrites()throws Exception{mvc.perform(post("/api/uploads/validated/not-uuid/apply")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("token"));noBusiness();verify(mapper,never()).insertUpload(any());}
    @Test void unknownUuidIsExpired()throws Exception{mvc.perform(post("/api/uploads/validated/"+UUID.randomUUID()+"/apply")).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("VALIDATION_EXPIRED"));noBusiness();}
    @ParameterizedTest @ValueSource(longs={1800,1801}) void expiresAtThirtyMinutes(long seconds)throws Exception{String token=token(customer("1234567890"));clock.now=clock.now.plusSeconds(seconds);mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isGone()).andExpect(jsonPath("$.message").value("검사 후 30분이 지났습니다. 다시 올려 주세요."));noBusiness();}
    @Test void validJustBeforeExpiration()throws Exception{String token=token(customer("1234567890"));clock.now=clock.now.plusSeconds(1799);mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isOk());}
    @Test void staleValidationRefusesAllRows()throws Exception{String token=token(customer("1234567890"),customer("2222222222"));when(mapper.duplicateName("123-45-67890")).thenReturn("다른 등록");mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VALIDATION")).andExpect(jsonPath("$.message").value("다시 검사해 주세요."));noBusiness();verify(mapper,never()).insertUpload(any());verify(transactions).rollback(any());}
    @Test void uniqueRaceRollsBackBeforeTranslatingAndClaimCanRetry()throws Exception{String token=token(customer("1234567890"));when(mapper.insertPartner(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("race"));mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VALIDATION"));verify(transactions).rollback(any());verify(transactions,never()).commit(any());doReturn(1L).when(mapper).insertPartner(any());mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isOk());}
    @Test void secondInsertFailureRollsBackTransactionAndDoesNotCompleteToken()throws Exception{String token=token(customer("1234567890"),customer("2222222222"));when(mapper.insertPartner(any())).thenReturn(1L).thenThrow(new IllegalStateException("fail"));mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isInternalServerError());verify(transactions).rollback(any());verify(transactions,never()).commit(any());verify(mapper,never()).access(any());}
    @ParameterizedTest @ValueSource(strings={"x.xls","x.csv"}) void rejectsExtension(String name)throws Exception{mvc.perform(multipart("/api/uploads/B.7/validate").file(new MockMultipartFile("file",name,"application/octet-stream",new byte[]{1}))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));noBusiness();}
    @Test void rejectsRenamedFileType()throws Exception{validate("not an xlsx".getBytes()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));}
    @Test void oversizedIs413()throws Exception{validate(new byte[11*1024*1024]).andExpect(status().isPayloadTooLarge());noBusiness();}
    @Test void missingHeaderIsFileError()throws Exception{validate(workbook(new String[]{"알 수 없음"},List.of(),0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HEADER_NOT_FOUND"));}
    @Test void missingRequiredColumnIsFileError()throws Exception{validate(workbook(new String[]{"구분","사업자번호"},List.of(),0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUIRED_COLUMN_MISSING"));}
    @Test void duplicateAliasColumnIsFileError()throws Exception{validate(workbook(new String[]{"구분","상호","사업자번호","사업자등록번호"},List.of(),0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUIRED_COLUMN_MISSING"));}
    @Test void emptyDataIsFileError()throws Exception{validate(file()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_FILE"));}
    @Test void emptyBytesIsEmptyFile()throws Exception{validate(new byte[0]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_FILE"));}
    @Test void tenThousandAndOneRowsIsFileError()throws Exception{var rows=new ArrayList<String[]>();for(int i=0;i<10001;i++)rows.add(customer("1234567890"));validate(workbook(PartnerUploadForm.HEADERS.toArray(String[]::new),rows,3)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"));verify(mapper,never()).insertUpload(any());}
    @Test void shuffledAliasesNumericBusinessAndFormulaAreReadAtActualRow()throws Exception{
        byte[] bytes=workbook(new String[]{"사업자등록번호","업체명","거래처 구분","계좌","은행명","예금주명"},Collections.singletonList(new String[]{"1234567890"," 협력 ","협력업체","12345678","국민은행","예금주"}),2);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()){book.getSheetAt(0).getRow(3).getCell(0).setCellValue(1234567890d);book.getSheetAt(0).getRow(3).getCell(1).setCellFormula("\" 협력 \"");book.write(out);bytes=out.toByteArray();}
        var result=data(validate(bytes));assertThat(result.get("errorRows").asInt()).isZero();assertThat(result.get("warningRows").asInt()).isEqualTo(1);assertThat(result.get("items").get(0).get("rowNo").asInt()).isEqualTo(4);String token=result.get("token").asText();service.apply(token);verify(mapper).insertPartner(argThat(w->w.input().biz_reg_no().equals("123-45-67890")&&w.input().ptnr_se_cd().equals("VEND")&&w.input().ptnr_nm().equals("협력")));
    }
    @Test void duplicateFileReportsOnlySecondAndOriginalRow()throws Exception{var result=data(validate(file(customer("1234567890"),customer("123-45-67890"))));assertThat(result.get("errorRows").asInt()).isEqualTo(1);assertThat(result.get("items").get(0).get("message").asText()).isEqualTo("파일 안에서 5행과 사업자번호가 같습니다.");assertThat(result.get("items").get(0).get("rowNo").asInt()).isEqualTo(6);}
    @Test void stoppedDatabaseKeyIsStillDuplicate()throws Exception{when(mapper.duplicateName("123-45-67890")).thenReturn("중지 거래처");var result=data(validate(file(customer("1234567890"))));assertThat(result.get("items").get(0).get("message").asText()).isEqualTo("이미 등록된 사업자번호입니다. (중지 거래처)");}
    @Test void examplesAndBlankRowsDoNotCount()throws Exception{String[] sample=customer("wrong");sample[1]="예시) 제외";var result=data(validate(file(sample,new String[0],customer("1234567890"))));assertThat(result.get("totalRows").asInt()).isEqualTo(1);assertThat(result.get("errorRows").asInt()).isZero();}
    @Test void nonApplicableInvalidValuesAreWarnedAndNull()throws Exception{String[] cust=customer("1234567890"),vend=vendor("2222222222");cust[8]="없는 은행";cust[9]="잘못된 계좌";cust[10]="가".repeat(100);vend[7]="잘못된 이메일";var result=data(validate(file(cust,vend)));assertThat(result.get("errorRows").asInt()).isZero();assertThat(result.get("warningRows").asInt()).isEqualTo(2);service.apply(result.get("token").asText());verify(mapper).insertPartner(argThat(w->w.input().ptnr_se_cd().equals("CUST")&&w.input().bank_cd()==null&&w.input().acnt_no()==null&&w.input().dpstr_nm()==null));verify(mapper).insertPartner(argThat(w->w.input().ptnr_se_cd().equals("VEND")&&w.input().txinv_email_addr()==null));}
    @ParameterizedTest @CsvSource({"1,51","3,21","5,21","6,21","7,101","11,501"}) void lengthViolationsRemainRowErrorsAndInputLedgerIsTruncated(int column,int length)throws Exception{var row=customer("1234567890");row[column]="가".repeat(length);var result=data(validate(file(row)));assertThat(result.get("errorRows").asInt()).isEqualTo(1);verify(mapper,atLeastOnce()).insertIssue(argThat(w->w.input().length()<=500));noBusiness();}
    @ParameterizedTest @ValueSource(ints={8,9,10}) void vendorConditionalFieldsAreRequired(int column)throws Exception{var row=vendor("1234567890");row[column]="";var result=data(validate(file(row)));assertThat(result.get("items").toString()).contains("협력사는 지급 계좌");assertThat(result.has("token")).isFalse();}
    @ParameterizedTest @ValueSource(strings={"","invalid"}) void customerEmailIsRequiredAndValidated(String email)throws Exception{var row=customer("1234567890");row[7]=email;assertThat(data(validate(file(row))).get("errorRows").asInt()).isEqualTo(1);}
    @Test void vendorAccountFormatError()throws Exception{var row=vendor("1234567890");row[9]="abc";assertThat(data(validate(file(row))).get("items").toString()).contains("숫자와 하이픈");}
    @Test void unknownKindHasMappingAction()throws Exception{var row=customer("1234567890");row[0]="모름";var result=data(validate(file(row)));assertThat(result.get("items").get(0).get("fix").get("group").asText()).isEqualTo("PARTNER_KIND");}
    @Test void ambiguousActiveMappingsDoNotChooseFirst()throws Exception{when(mapper.mappings()).thenReturn(List.of(new UploadMapping("PARTNER_KIND","업체","CUST"),new UploadMapping("PARTNER_KIND","업체","VEND")));var row=customer("1234567890");row[0]="업체";assertThat(data(validate(file(row))).get("errorRows").asInt()).isEqualTo(1);}
    @Test void latestMappingRecheckReadsSameOriginalAndDoesNotOverwriteRejectedLedger()throws Exception{
        var row=customer("1234567890");row[4]="핀테크";data(validate(file(row)));var captor=org.mockito.ArgumentCaptor.forClass(UploadWrite.class);verify(mapper).insertUpload(captor.capture());var source=captor.getValue().context();when(mapper.source(7)).thenReturn(new UploadSource(source.fileName(),source.path()));when(mapper.mappings()).thenReturn(List.of(new UploadMapping("INDUSTRY","핀테크","SI")));
        var result=data(mvc.perform(post("/api/uploads/7/recheck")));assertThat(result.has("token")).isTrue();assertThat(result.get("fileName").asText()).isEqualTo("거래처.xlsx");assertThat(result.get("warningRows").asInt()).isEqualTo(1);verify(mapper,times(1)).insertUpload(any());noBusiness();
    }
    @Test void remainingErrorsCreateNewRejectedLedgerOnRecheck()throws Exception{data(validate(file(customer("bad"))));var captor=org.mockito.ArgumentCaptor.forClass(UploadWrite.class);verify(mapper).insertUpload(captor.capture());var ctx=captor.getValue().context();when(mapper.source(7)).thenReturn(new UploadSource(ctx.fileName(),ctx.path()));data(mvc.perform(post("/api/uploads/7/recheck")));verify(mapper,times(2)).insertUpload(any());noBusiness();}
    @Test void unknownRecheckAndErrorFileAre404()throws Exception{mvc.perform(post("/api/uploads/999/recheck")).andExpect(status().isNotFound());mvc.perform(get("/api/uploads/999/error-file")).andExpect(status().isNotFound());noBusiness();}
    @Test void missingOriginalDoesNotLeakPath()throws Exception{when(mapper.source(7)).thenReturn(new UploadSource("lost.xlsx","/not/real/private.xlsx"));mvc.perform(post("/api/uploads/7/recheck")).andExpect(status().isNotFound()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/not/real"))));}
    @Test void errorWorkbookPreservesOrderRoundTripsAndCorrectedFileGetsToken()throws Exception{
        var row=customer("bad");var result=data(validate(file(row)));var upload=org.mockito.ArgumentCaptor.forClass(UploadWrite.class);var issue=org.mockito.ArgumentCaptor.forClass(IssueWrite.class);verify(mapper).insertUpload(upload.capture());verify(mapper).insertIssue(issue.capture());var ctx=upload.getValue().context();when(mapper.source(7)).thenReturn(new UploadSource(ctx.fileName(),ctx.path()));when(mapper.issues(7)).thenReturn(List.of(issue.getValue().issue()));
        byte[] bytes=mvc.perform(get("/api/uploads/7/error-file")).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn().getResponse().getContentAsByteArray();
        assertThat(data(validate(bytes)).get("items").get(0).get("message")).isEqualTo(result.get("items").get(0).get("message"));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()){assertThat(book.getNumberOfSheets()).isEqualTo(2);assertThat(book.getSheetAt(0).getRow(3).getCell(12).getStringCellValue()).isEqualTo("오류 사유");assertThat(book.getSheetAt(0).getRow(4).getCell(2).getCellStyle().getFillForegroundColor()).isEqualTo(IndexedColors.ROSE.getIndex());book.getSheetAt(0).getRow(4).getCell(2).setCellValue("1234567890");book.write(out);assertThat(data(validate(out.toByteArray())).has("token")).isTrue();}
        verify(mapper).access(argThat(a->a.type().equals("DOWNLOAD")&&a.display().equals("거래처_오류.xlsx")&&a.detail().contains("upldId")&&!a.detail().contains("path")));noBusiness();
    }
    @Test void streamedErrorListPreservesAllIssuesAndSourceBeyondWindow()throws Exception {
        var rows=new ArrayList<String[]>();var issues=new ArrayList<UploadIssue>();
        for(int i=0;i<201;i++) {
            String[] row=customer("bad-"+i);row[1]="거래처 "+i;rows.add(row);
            issues.add(new UploadIssue("ERROR",i+5,"사업자번호",row[2],"사업자번호를 확인해 주세요.",null));
        }
        byte[] original=workbook(PartnerUploadForm.HEADERS.toArray(String[]::new),rows,3);
        Path path=downloadDirectory.resolve("original.xlsx");Files.write(path,original);
        when(mapper.source(7)).thenReturn(new UploadSource("거래처.XLSX",path.toString()));when(mapper.issues(7)).thenReturn(issues);
        var response=mvc.perform(get("/api/uploads/7/error-file")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(org.springframework.http.ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename()).isEqualTo("거래처_오류.xlsx");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(2);assertThat(book.getSheetAt(1).getSheetName()).isEqualTo("오류 목록");
            assertThat(book.getSheetAt(1).getLastRowNum()).isEqualTo(201);
            for(int i=0;i<201;i++) {
                assertThat(book.getSheetAt(0).getRow(i+4).getCell(1).getStringCellValue()).isEqualTo("거래처 "+i);
                assertThat(book.getSheetAt(0).getRow(i+4).getCell(12).getStringCellValue()).isEqualTo(issues.get(i).message());
                assertThat(book.getSheetAt(1).getRow(i+1).getCell(1).getNumericCellValue()).isEqualTo(i+5d);
                assertThat(book.getSheetAt(1).getRow(i+1).getCell(3).getStringCellValue()).isEqualTo("bad-"+i);
            }
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(original);
        var audit=org.mockito.ArgumentCaptor.forClass(UploadAccess.class);verify(mapper,times(1)).access(audit.capture());
        assertThat(audit.getValue().type()).isEqualTo("DOWNLOAD");assertThat(audit.getValue().actor()).isNull();
        assertThat(json.readTree(audit.getValue().detail())).isEqualTo(json.readTree("{\"upldId\":\"7\",\"fileName\":\"거래처_오류.xlsx\"}"));
        verify(mapper,never()).insertUpload(any());verify(mapper,never()).insertIssue(any());noBusiness();
    }
    @Test void errorFileRetainsAliasesBlankRowsTextAndOnlyHighlightsErrors()throws Exception {
        String[] headers={"사업자등록번호","업체명","거래처 구분","계좌","은행명","예금주명"};
        byte[] original=workbook(headers,Arrays.asList(new String[]{"bad","협력","VEND","001-23456789","국민은행","예금주"},new String[0],new String[]{"bad","두번째","VEND"}),2);
        var issues=List.of(new UploadIssue("ERROR",4,"사업자번호","bad","번호 오류",null),new UploadIssue("WARN",4,"은행","국민은행","표기 경고",null),new UploadIssue("ERROR",6,"사업자번호","=WEBSERVICE(\"https://invalid.test\")","번호 오류",null));
        Path path=downloadDirectory.resolve("aliases.xlsx");Files.write(path,original);when(mapper.source(7)).thenReturn(new UploadSource("aliases.xlsx",path.toString()));when(mapper.issues(7)).thenReturn(issues);
        byte[] bytes=mvc.perform(get("/api/uploads/7/error-file")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=book.getSheetAt(0);assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("사업자등록번호");
            assertThat(sheet.getRow(3).getCell(3).getStringCellValue()).isEqualTo("001-23456789");assertThat(sheet.getRow(4).getPhysicalNumberOfCells()).isZero();
            assertThat(sheet.getRow(3).getCell(6).getStringCellValue()).isEqualTo("번호 오류 / 표기 경고");
            assertThat(sheet.getRow(3).getCell(0).getCellStyle().getFillForegroundColor()).isEqualTo(IndexedColors.ROSE.getIndex());
            assertThat(sheet.getRow(3).getCell(4).getCellStyle().getFillPattern()).isEqualTo(FillPatternType.NO_FILL);
            assertThat(book.getSheetAt(1).getRow(3).getCell(3).getCellType()).isEqualTo(CellType.STRING);assertThat(book.getExternalLinksTable()).isEmpty();
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(original);noBusiness();
    }
    @Test void missingErrorFileOriginalReturnsJson404WithoutAudit()throws Exception {
        when(mapper.source(7)).thenReturn(new UploadSource("lost.xlsx",downloadDirectory.resolve("lost.xlsx").toString()));
        mvc.perform(get("/api/uploads/7/error-file")).andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.message").value("원본 파일이 없습니다. 다시 올려 주세요.")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(downloadDirectory.toString()))));
        verify(mapper,never()).access(any());verify(mapper,never()).issues(anyLong());noBusiness();
    }
    @Test void corruptStoredWorkbookCannotProduceSuccessOrDownloadAudit()throws Exception {
        Path path=downloadDirectory.resolve("corrupt.xlsx");Files.writeString(path,"not a workbook");when(mapper.source(7)).thenReturn(new UploadSource("corrupt.xlsx",path.toString()));
        mvc.perform(get("/api/uploads/7/error-file")).andExpect(status().isInternalServerError()).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(path.toString()))));
        verify(mapper,never()).access(any());noBusiness();
    }
    @Test void filenameUsesBasenameAndRejectsOverlongName()throws Exception{var bytes=file(customer("1234567890"));var result=data(mvc.perform(multipart("/api/uploads/B.7/validate").file(new MockMultipartFile("file","../../safe.xlsx","application/octet-stream",bytes))));assertThat(result.get("fileName").asText()).isEqualTo("safe.xlsx");mvc.perform(multipart("/api/uploads/B.7/validate").file(new MockMultipartFile("file","a".repeat(256)+".xlsx","application/octet-stream",bytes))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"));}
    @Test void concurrentSameTokenClaimsOnlyOneTransaction()throws Exception {
        String token=token(customer("1234567890"));CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        when(mapper.insertPartner(any())).thenAnswer(invocation->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return 1L;});
        try {var first=pool.submit(()->service.apply(token));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var second=pool.submit(()->service.apply(token));release.countDown();assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo(second.get(10,TimeUnit.SECONDS));verify(mapper,times(1)).insertUpload(any());verify(mapper,times(1)).access(any());}
        finally{release.countDown();pool.shutdownNow();}
    }
    @Test void externalEntityWorkbookIsRejectedWithoutBusinessOrLedgerWrites()throws Exception {
        byte[] source=file(customer("1234567890"));ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(var input=new java.util.zip.ZipInputStream(new ByteArrayInputStream(source));var zip=new java.util.zip.ZipOutputStream(out)) {
            java.util.zip.ZipEntry entry;while((entry=input.getNextEntry())!=null){byte[] bytes=input.readAllBytes();if(entry.getName().equals("xl/workbook.xml")){String xml=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);xml=xml.replaceFirst("\\?>","?>\\n<!DOCTYPE workbook [<!ENTITY forbidden SYSTEM 'file:///forbidden-upload-entity'>]>");bytes=xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);}zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));zip.write(bytes);zip.closeEntry();}
        }
        validate(out.toByteArray()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));noBusiness();verify(mapper,never()).insertUpload(any());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void identifierFormatsPreserveZerosAndHyphensWhileBusinessNumberUsesInteger(boolean formula)throws Exception {
        byte[] bytes=file(vendor("1234567890"));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()) {
            Row row=book.getSheetAt(0).getRow(4);
            row.getCell(2).setCellValue(1234567890d);
            var scientific=book.createCellStyle();scientific.setDataFormat(book.createDataFormat().getFormat("0.00E+00"));row.getCell(2).setCellStyle(scientific);
            for(int column:new int[]{6,9}) {
                var cell=row.getCell(column);double number=column==6?1012345678d:12345678d;
                if(formula)cell.setCellFormula(Double.toString(number));else cell.setCellValue(number);
                var style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat(column==6?"000-0000-0000":"000-00000000"));cell.setCellStyle(style);
            }
            book.write(out);bytes=out.toByteArray();
        }
        var result=data(validate(bytes));assertThat(result.get("errorRows").asInt()).isZero();
        mvc.perform(post("/api/uploads/validated/"+result.get("token").asText()+"/apply")).andExpect(status().isOk());
        verify(mapper).insertPartner(argThat(w->w.input().biz_reg_no().equals("123-45-67890")&&w.input().chrg_tel_no().equals("010-1234-5678")&&w.input().acnt_no().equals("000-12345678")));
    }
    @Test void textIdentifiersRemainLiteralIncludingLeadingZeros()throws Exception {
        var row=vendor("1234567890");row[6]=" 010-1234-5678 ";row[9]=" 001-23456789 ";
        service.apply(data(validate(file(row))).get("token").asText());
        verify(mapper).insertPartner(argThat(w->w.input().chrg_tel_no().equals("010-1234-5678")&&w.input().acnt_no().equals("001-23456789")));
    }
    @ParameterizedTest @ValueSource(strings={"codes","mappings","duplicate"})
    void databaseReadFailureIsServerErrorNotAnInvalidWorkbook(String boundary)throws Exception {
        var failure=new org.springframework.dao.DataAccessResourceFailureException("private database details");
        switch(boundary) {case "codes" -> when(mapper.codes()).thenThrow(failure);case "mappings" -> when(mapper.mappings()).thenThrow(failure);default -> when(mapper.duplicateName(anyString())).thenThrow(failure);}
        validate(file(customer("1234567890"))).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SERVER_ERROR")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
        noBusiness();verify(mapper,never()).insertUpload(any());
    }
    @ParameterizedTest @ValueSource(ints={19,20}) void headerSearchIncludesOnlyFirstTwentyRows(int headerRow)throws Exception {
        var response=validate(workbook(PartnerUploadForm.HEADERS.toArray(String[]::new),Collections.singletonList(customer("1234567890")),headerRow));
        if(headerRow==19)assertThat(data(response).get("errorRows").asInt()).isZero();
        else response.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HEADER_NOT_FOUND"));
        noBusiness();
    }
    @Test void allSpecifiedAliasesMapToTheTwelveStoredFields()throws Exception {
        String[] headers={"거래처 구분","회사명","사업자 번호","대표자명","업종","담당자명","전화","계산서 이메일","은행명","계좌","예금주명","메모"};
        String[] row={"둘 다","별칭 거래처","1234567890","대표","SI","담당","010-1234-5678","tax@company.co.kr","국민은행","001-23456789","예금주","메모 내용"};
        service.apply(data(validate(workbook(headers,Collections.singletonList(row),2))).get("token").asText());
        verify(mapper).insertPartner(argThat(w->w.input().equals(new com.sicost.partners.PartnerModels.PartnerSave("BOTH","별칭 거래처","123-45-67890","대표","SI","담당","010-1234-5678","메모 내용","tax@company.co.kr","KB","001-23456789","예금주"))));
    }
    @Test void exactlyTenThousandDataRowsExcludesExampleAndBlankRows()throws Exception {
        var rows=new ArrayList<String[]>();var example=customer("bad");example[1]="예시) 제외";rows.add(example);rows.add(new String[0]);
        for(int i=0;i<10000;i++)rows.add(customer(String.format(Locale.ROOT,"%010d",i)));
        var result=data(validate(workbook(PartnerUploadForm.HEADERS.toArray(String[]::new),rows,3)));
        assertThat(result.get("totalRows").asInt()).isEqualTo(10000);assertThat(result.get("errorRows").asInt()).isZero();assertThat(result.has("token")).isTrue();noBusiness();verify(mapper,never()).insertUpload(any());
    }
    @Test void exactlyTenBinaryMegabytesPassesSizeGate()throws Exception {
        var file=new MockMultipartFile("file","거래처.xlsx","application/octet-stream",file(customer("1234567890"))) {@Override public long getSize(){return 10L*1024*1024;}};
        mvc.perform(multipart("/api/uploads/B.7/validate").file(file)).andExpect(status().isOk()).andExpect(jsonPath("$.data.errorRows").value(0));noBusiness();
    }
    @ParameterizedTest @CsvSource({"1,50","3,20","5,20","6,20","10,30","11,500"})
    void lengthErrorUsesSpecifiedColumnLimitAndActualCell(int column,int max)throws Exception {
        var row=vendor("1234567890");row[column]="가".repeat(max+1);
        var result=data(validate(file(row)));var issue=result.get("items").get(0);
        assertThat(issue.get("severity").asText()).isEqualTo("ERROR");assertThat(issue.get("rowNo").asInt()).isEqualTo(5);assertThat(issue.get("column").asText()).isEqualTo(PartnerUploadForm.HEADERS.get(column));assertThat(issue.get("message").asText()).isEqualTo(PartnerUploadForm.HEADERS.get(column)+"는 "+max+"자까지 쓸 수 있습니다.");assertThat(result.has("token")).isFalse();noBusiness();
    }
    @ParameterizedTest @CsvSource({"4,INDUSTRY,업종","8,BANK,은행"})
    void unknownCodeHasExactMessageAndSameGroupMappingAction(int column,String group,String title)throws Exception {
        var row=vendor("1234567890");row[column]="미등록";var result=data(validate(file(row)));var issue=result.get("items").get(0);
        assertThat(issue.get("column").asText()).isEqualTo(title);assertThat(issue.get("message").asText()).isEqualTo("'미등록'은(는) 등록된 "+title+"이 아닙니다. 목록에 있는 이름으로 적거나 [대응 등록]으로 연결해 주세요.");assertThat(issue.get("fix").get("group").asText()).isEqualTo(group);assertThat(result.has("token")).isFalse();noBusiness();
    }
    @Test void everyMappedColumnWarnsButOnlyOneWarningRowIsCounted()throws Exception {
        when(mapper.mappings()).thenReturn(List.of(new UploadMapping("PARTNER_KIND","협력업체","VEND"),new UploadMapping("INDUSTRY","핀테크","SI"),new UploadMapping("BANK","국민","KB")));
        var row=vendor("1234567890");row[0]="협력업체";row[4]="핀테크";row[8]="국민";var result=data(validate(file(row)));
        assertThat(result.get("warningRows").asInt()).isEqualTo(1);assertThat(result.get("items").size()).isEqualTo(3);assertThat(result.get("errorRows").asInt()).isZero();
        assertThat(result.get("items").get(0).get("message").asText()).isEqualTo("'협력업체'를 '협력사'(으)로 바꿔 읽었습니다.");service.apply(result.get("token").asText());verify(mapper,times(3)).insertIssue(argThat(w->w.issue().severity().equals("WARN")));
    }
    @Test void completedTokenAlsoExpiresAtBoundaryWithoutDeletingOriginal()throws Exception {
        String token=token(customer("1234567890"));service.apply(token);var write=org.mockito.ArgumentCaptor.forClass(UploadWrite.class);verify(mapper).insertUpload(write.capture());
        Path path=Path.of(write.getValue().context().path());clock.now=clock.now.plusSeconds(1800);
        mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("VALIDATION_EXPIRED"));assertThat(path).exists();verify(mapper,times(1)).insertUpload(any());
    }
    @Test void commitFailureDoesNotCacheCompletedResult()throws Exception {
        String token=token(customer("1234567890"));doThrow(new IllegalStateException("commit failed")).doNothing().when(transactions).commit(any());
        mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isInternalServerError());
        mvc.perform(post("/api/uploads/validated/"+token+"/apply")).andExpect(status().isOk());verify(mapper,times(2)).insertUpload(any());verify(transactions,times(2)).commit(any());
    }
}
