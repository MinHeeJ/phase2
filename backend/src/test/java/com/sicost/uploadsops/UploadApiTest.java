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
}
