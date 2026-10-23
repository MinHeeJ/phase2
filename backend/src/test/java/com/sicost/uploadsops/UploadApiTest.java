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
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
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
@Import({RequestIdFilter.class,ApiExceptionHandler.class,UploadService.class,UploadStorage.class,CurrentUser.class,PermissionService.class,UploadApiTest.Time.class})
class UploadApiTest {
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-22T00:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @TestConfiguration static class Time {@Bean MutableClock clock(){return new MutableClock();}}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @MockBean UploadMapper mapper;
    @MockBean PlatformTransactionManager manager;
    @BeforeEach void setup() {
        clock.now=Instant.parse("2026-10-22T00:00:00Z");
        when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        when(mapper.codes()).thenReturn(codes());when(mapper.mappings()).thenReturn(List.of());
        AtomicLong id=new AtomicLong();when(mapper.insertLedger(any())).thenAnswer(i->id.incrementAndGet());
        AtomicLong partner=new AtomicLong();when(mapper.reservePartnerId()).thenAnswer(i->partner.incrementAndGet());
    }
    static List<Code> codes(){return List.of(new Code("PARTNER_KIND","CUST","고객사",null),new Code("PARTNER_KIND","VEND","협력사",null),new Code("PARTNER_KIND","BOTH","둘 다",null),new Code("INDUSTRY","SI","SI",null),new Code("BANK","KB","국민은행",null));}
    static String[] customer(String number){return new String[]{"고객사","거래처",number,"","","","","tax@company.co.kr","","","",""};}
    static String[] vendor(String number){return new String[]{"협력사","거래처",number,"","","","","","국민은행","123-45678","예금주",""};}
    static byte[] workbook(String[] headers,int headerRow,List<String[]> rows)throws Exception {
        try(var book=new SXSSFWorkbook();var out=new ByteArrayOutputStream()) {
            try {Sheet sheet=book.createSheet("거래처");Row h=sheet.createRow(headerRow);for(int c=0;c<headers.length;c++)h.createCell(c).setCellValue(headers[c]);int n=headerRow+1;for(String[] values:rows){Row r=sheet.createRow(n++);for(int c=0;c<values.length;c++)r.createCell(c).setCellValue(values[c]);}book.write(out);return out.toByteArray();}finally{book.dispose();}
        }
    }
    static byte[] file(String[]...rows)throws Exception{return workbook(PartnerUploadForm.HEADERS,3,Arrays.asList(rows));}
    ResultActions upload(byte[] bytes)throws Exception{return mvc.perform(multipart("/api/uploads/B.7/validate").file(new MockMultipartFile("file","거래처.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)));}
    JsonNode result(ResultActions actions)throws Exception {return json.readTree(actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).get("data");}
    JsonNode validate(String[]...rows)throws Exception{return result(upload(file(rows)));}
    void noBusinessWrites(){verify(mapper,never()).insertPartner(any());verify(mapper,never()).insertHistory(any());verify(mapper,never()).insertAccess(any());}
    @Test void publicTemplateIsRealWorkbookWithListsFormatsProtectionAndOneAudit()throws Exception {
        var response=mvc.perform(get("/api/uploads/templates/B.7")).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn().getResponse();
        assertThat(org.springframework.http.ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename()).isEqualTo("거래처_업로드양식.xlsx");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(3);Sheet sheet=book.getSheetAt(0);assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("* 표시는 필수");assertThat(sheet.getRow(1)).isNotNull();assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).contains("상호","*");assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).startsWith("예시)");assertThat(sheet.getColumnStyle(2).getDataFormatString()).isEqualTo("@");assertThat(sheet.getColumnStyle(9).getDataFormatString()).isEqualTo("@");assertThat(sheet.getDataValidations()).hasSize(3);assertThat(book.getSheet("목록").getProtect()).isTrue();
        }
        verify(mapper).insertAccess(argThat(w->w.type().equals("DOWNLOAD")&&w.fileName().equals("거래처_업로드양식.xlsx")&&w.actor()==null&&w.detail().contains("기본 사용자")));verify(mapper,never()).insertPartner(any());
    }
    @Test void unknownTemplateIs404WithoutAudit()throws Exception{mvc.perform(get("/api/uploads/templates/OTHER")).andExpect(status().isNotFound());verify(mapper,never()).insertAccess(any());}
    @Test void publicValidFiveRowsThreeWarningsHaveTokenWithoutLedger()throws Exception {
        String[][] rows=new String[5][];for(int i=0;i<5;i++){rows[i]=customer("123450000"+i);if(i<3)rows[i][8]="무시";}
        JsonNode data=validate(rows);assertThat(data.get("totalRows").asInt()).isEqualTo(5);assertThat(data.get("warningRows").asInt()).isEqualTo(3);assertThat(data.get("errorRows").asInt()).isZero();UUID.fromString(data.get("token").asText());assertThat(data.has("upldId")).isFalse();verify(mapper,never()).insertLedger(any());noBusinessWrites();
    }
    @Test void sixRowsFiveErrorsPersistEveryMessageWithoutBusinessMutation()throws Exception {
        String[][] rows=new String[6][];for(int i=0;i<6;i++){rows[i]=customer("123450000"+i);if(i>0)rows[i][1]="";}
        var response=mvc.perform(multipart("/api/uploads/B.7/validate")
            .file(new MockMultipartFile("file","거래처.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",file(rows))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalRows").value(6))
            .andExpect(jsonPath("$.data.okRows").value(1)).andExpect(jsonPath("$.data.errorRows").value(5))
            .andExpect(jsonPath("$.data.upldId").isString()).andExpect(jsonPath("$.data.token").doesNotExist()).andReturn();
        JsonNode data=json.readTree(response.getResponse().getContentAsByteArray()).get("data");
        assertThat(data.toString()).doesNotContain("file_path","/sicost/uploads");
        verify(mapper).insertLedger(argThat(w->w.status().equals("REJECTED")));
        verify(mapper,times(5)).insertIssue(argThat(issue->issue.issue().severity().equals("ERROR")));noBusinessWrites();
    }
    @Test void errorAndWarningCountsCanOverlapOnOneRow()throws Exception {String[] row=customer("1234500000");row[1]="";row[8]="무시";JsonNode data=validate(row);assertThat(data.get("errorRows").asInt()).isEqualTo(1);assertThat(data.get("warningRows").asInt()).isEqualTo(1);assertThat(data.get("okRows").asInt()).isZero();assertThat(data.get("items")).hasSize(2);}
    @Test void aliasesReorderedThirdRowHeaderNumericBusinessFormulaAndExampleAreSupported()throws Exception {
        String[] headers={"사업자등록번호 *","업체명","거래처 구분","계산서 이메일"};
        byte[] bytes=workbook(headers,2,List.of(new String[]{"1234500000","거래처","고객사","tax@company.co.kr"},new String[]{"","예시) 제외","",""}));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()) {book.getSheetAt(0).getRow(3).getCell(0).setCellValue(1234500000d);book.getSheetAt(0).getRow(3).getCell(1).setCellFormula("\"거래처\"");book.write(out);bytes=out.toByteArray();}
        JsonNode data=result(upload(bytes));assertThat(data.get("totalRows").asInt()).isEqualTo(1);assertThat(data.get("errorRows").asInt()).isZero();result(mvc.perform(post("/api/uploads/validated/"+data.get("token").asText()+"/apply")));verify(mapper).insertPartner(argThat(w->w.row().businessNo().equals("123-45-00000")&&w.row().rowNo()==4));
    }
    @Test void duplicateOnlySecondRowAndMappingWarnRetainActualRow()throws Exception {when(mapper.mappings()).thenReturn(List.of(new Code("PARTNER_KIND","VEND","협력사","협력업체")));String[] first=vendor("1234500000"),second=vendor("1234500000");first[0]="협력업체";JsonNode data=validate(first,second);assertThat(data.get("errorRows").asInt()).isEqualTo(1);assertThat(data.get("warningRows").asInt()).isEqualTo(1);assertThat(data.toString()).contains("파일 안에서 5행과","바꿔 읽었습니다.","협력사").doesNotContain("file_path_addr");}
    @Test void existingStoppedPartnerIsDuplicate()throws Exception {when(mapper.existingPartner("123-45-00000")).thenReturn("중지 거래처");JsonNode data=validate(customer("1234500000"));assertThat(data.toString()).contains("이미 등록된 사업자번호입니다. (중지 거래처)");}
    @ParameterizedTest @CsvSource({"0,알수없음,구분은 고객사","1,'',상호가 비어 있습니다.","2,123,사업자번호 10자리","7,'',고객사는 세금계산서 이메일","7,invalid,이메일 형식이 맞지 않습니다.","4,핀테크,등록된 업종"})
    void eachCustomerRuleIsRowError(int column,String value,String message)throws Exception {String[] row=customer("1234500000");row[column]=value;JsonNode data=validate(row);assertThat(data.get("errorRows").asInt()).isEqualTo(1);assertThat(data.toString()).contains(message);noBusinessWrites();}
    @ParameterizedTest @CsvSource({"1,51","3,21","5,21","6,21","11,501"})
    void eachTextLengthIsEnforced(int column,int length)throws Exception {String[] row=customer("1234500000");row[column]="가".repeat(length);JsonNode data=validate(row);assertThat(data.get("errorRows").asInt()).isEqualTo(1);assertThat(data.toString()).contains(PartnerUploadForm.HEADERS[column]);}
    @ParameterizedTest @CsvSource({"8,''","9,''","10,''","9,abcd1234","9,1234567","9,123456789012345678901","8,없는은행","10,1234567890123456789012345678901"})
    void eachVendorAccountRuleBlocksApply(int column,String value)throws Exception{String[] row=vendor("1234500000");row[column]=value;JsonNode data=validate(row);assertThat(data.get("errorRows").asInt()).isEqualTo(1);assertThat(data.has("token")).isFalse();}
    @Test void nonApplicableFieldsSkipValidationAndBecomeNull()throws Exception {String[] customer=customer("1234500000"),vendor=vendor("1234500001");customer[8]="없는은행";customer[9]="wrong";customer[10]="a".repeat(100);vendor[7]="bad";JsonNode data=validate(customer,vendor);assertThat(data.get("errorRows").asInt()).isZero();assertThat(data.get("warningRows").asInt()).isEqualTo(2);result(mvc.perform(post("/api/uploads/validated/"+data.get("token").asText()+"/apply")));verify(mapper).insertPartner(argThat(w->w.row().kind().equals("CUST")&&w.row().bank()==null&&w.row().account()==null&&w.row().depositor()==null));verify(mapper).insertPartner(argThat(w->w.row().kind().equals("VEND")&&w.row().email()==null));}
    @Test void ambiguousMappingNeverChoosesArbitraryTarget()throws Exception {when(mapper.mappings()).thenReturn(List.of(new Code("PARTNER_KIND","CUST","고객사","같음"),new Code("PARTNER_KIND","VEND","협력사","같음")));String[] row=customer("1234500000");row[0]="같음";JsonNode data=validate(row);assertThat(data.get("errorRows").asInt()).isEqualTo(1);assertThat(data.get("items").get(0).get("fix").get("group").asText()).isEqualTo("PARTNER_KIND");}
    @Test void inactiveMappingTargetIsNotResurrected()throws Exception {when(mapper.mappings()).thenReturn(List.of(new Code("INDUSTRY","INACTIVE","중지","핀테크")));String[] row=customer("1234500000");row[4]="핀테크";assertThat(validate(row).get("errorRows").asInt()).isEqualTo(1);}
    @Test void unknownFormValidateIs404()throws Exception {mvc.perform(multipart("/api/uploads/OTHER/validate").file(new MockMultipartFile("file","a.xlsx","application/octet-stream",file(customer("1234500000"))))).andExpect(status().isNotFound());}
    @Test void missingMultipartFileIs400()throws Exception {
        mvc.perform(post("/api/uploads/B.7/validate").contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("file")).andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(mapper);
    }
    @Test void renamedNonOoxmlIsRejected()throws Exception {upload("not an xlsx".getBytes()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));noBusinessWrites();}
    @Test void xlsExtensionIsRejected()throws Exception {mvc.perform(multipart("/api/uploads/B.7/validate").file(new MockMultipartFile("file","a.xls","application/octet-stream",file(customer("1234500000"))))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));}
    @Test void elevenMiBIs413()throws Exception{upload(new byte[11*1024*1024]).andExpect(status().isPayloadTooLarge());noBusinessWrites();}
    @Test void absentHeaderIsFileError()throws Exception {upload(workbook(new String[]{"없음"},0,List.<String[]>of(new String[]{"값"}))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HEADER_NOT_FOUND"));}
    @Test void missingNameColumnIsFileError()throws Exception {upload(workbook(new String[]{"사업자번호","구분"},0,List.<String[]>of(new String[]{"1234500000","고객사"}))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUIRED_COLUMN_MISSING"));}
    @Test void emptyRowsAreFileError()throws Exception{upload(file()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_FILE"));}
    @Test void exactTenMiBValidXlsxIsAllowed()throws Exception {
        byte[] bytes=Arrays.copyOf(file(customer("1234500000")),10*1024*1024);
        assertThat(result(upload(bytes)).has("token")).isTrue();
    }
    @Test void tenThousandDataRowsAreAllowed()throws Exception {
        List<String[]> rows=new ArrayList<>();for(int i=0;i<10000;i++)rows.add(customer(String.format(Locale.ROOT,"%010d",i)));
        JsonNode data=result(upload(workbook(PartnerUploadForm.HEADERS,3,rows)));assertThat(data.get("totalRows").asInt()).isEqualTo(10000);assertThat(data.has("token")).isTrue();
    }
    @Test void externalWorkbookReferenceIsForbidden()throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(file(customer("1234500000"))));var external=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            external.createSheet("External").createRow(0).createCell(0).setCellValue("회사");
            book.linkExternalWorkbook("external.xlsx",external);book.getSheetAt(0).getRow(4).getCell(1).setCellFormula("'[external.xlsx]External'!A1");book.write(out);
            upload(out.toByteArray()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));noBusinessWrites();
        }
    }
    @Test void mapperXmlParsesAllStatementsAndExplicitConstructors()throws Exception {
        var configuration=new org.apache.ibatis.session.Configuration();
        try(var in=new org.springframework.core.io.ClassPathResource("mappers/uploads/UploadMapper.xml").getInputStream()) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(in,configuration,"mappers/uploads/UploadMapper.xml",configuration.getSqlFragments()).parse();
        }
        assertThat(configuration.hasStatement("com.sicost.uploadsops.UploadMapper.insertLedger")).isTrue();
        assertThat(configuration.getResultMap("com.sicost.uploadsops.UploadMapper.uploadIssue").getConstructorResultMappings()).hasSize(5);
    }
    @Test void tenThousandAndOneRowsAreRejected()throws Exception{List<String[]> rows=new ArrayList<>();for(int i=0;i<10001;i++)rows.add(customer(String.format(Locale.ROOT,"%010d",i)));upload(workbook(PartnerUploadForm.HEADERS,3,rows)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"));verify(mapper,never()).insertLedger(any());}
    @Test void applyWritesInRowOrderWithWarningsHistoryAndOneAccessAndReplaysWithoutWrites()throws Exception {
        String[][] rows=new String[5][];for(int i=0;i<5;i++){rows[i]=customer("123450000"+i);if(i<3)rows[i][8]="무시";}
        JsonNode data=validate(rows);String path="/api/uploads/validated/"+data.get("token").asText()+"/apply";JsonNode applied=result(mvc.perform(post(path)));assertThat(applied.get("appliedRows").asInt()).isEqualTo(5);assertThat(result(mvc.perform(post(path)))).isEqualTo(applied);
        verify(mapper,times(1)).insertLedger(argThat(w->w.status().equals("APPLIED")));verify(mapper,times(5)).insertPartner(any());verify(mapper,times(3)).insertIssue(any());verify(mapper,times(5)).insertHistory(argThat(w->w.uploadId()==1&&w.value().contains("ptnr_nm")));verify(mapper).insertAccess(argThat(w->w.type().equals("UPLOAD")&&w.actor()==null));
        var writes=org.mockito.ArgumentCaptor.forClass(PartnerWrite.class);verify(mapper,times(5)).insertPartner(writes.capture());assertThat(writes.getAllValues()).extracting(PartnerWrite::no).containsExactly("C-001","C-002","C-003","C-004","C-005");verify(manager).commit(any());
    }
    @Test void codesAtThousandAreNotTruncated()throws Exception {when(mapper.reservePartnerId()).thenReturn(1000L);JsonNode data=validate(customer("1234500000"));result(mvc.perform(post("/api/uploads/validated/"+data.get("token").asText()+"/apply")));verify(mapper).insertPartner(argThat(w->w.no().equals("C-1000")));}
    @Test void invalidUuidDoesNotWrite()throws Exception {mvc.perform(post("/api/uploads/validated/not-uuid/apply")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("token"));verify(mapper,never()).insertLedger(any());noBusinessWrites();}
    @Test void missingUuidIsExpired()throws Exception {mvc.perform(post("/api/uploads/validated/"+UUID.randomUUID()+"/apply")).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("VALIDATION_EXPIRED"));noBusinessWrites();}
    @Test void exactThirtyMinuteBoundaryIsExpired()throws Exception {JsonNode data=validate(customer("1234500000"));clock.now=clock.now.plusSeconds(1800);mvc.perform(post("/api/uploads/validated/"+data.get("token").asText()+"/apply")).andExpect(status().isGone()).andExpect(jsonPath("$.message").value("검사 후 30분이 지났습니다. 다시 올려 주세요."));noBusinessWrites();}
    @Test void newlyRegisteredKeyConflictsBeforeAnyWrites()throws Exception {JsonNode data=validate(customer("1234500000"),customer("1234500001"));when(mapper.existingPartner("123-45-00000")).thenReturn("다른 등록");mvc.perform(post("/api/uploads/validated/"+data.get("token").asText()+"/apply")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VALIDATION"));verify(mapper,never()).insertLedger(any());noBusinessWrites();verify(manager).rollback(any());}
    @Test void uniqueRaceIsTranslatedOnlyAfterRollbackAndTokenCanRetry()throws Exception {JsonNode data=validate(customer("1234500000"));doThrow(new org.springframework.dao.DuplicateKeyException("race",new java.sql.SQLException("private","23505"))).doNothing().when(mapper).insertPartner(any());String path="/api/uploads/validated/"+data.get("token").asText()+"/apply";mvc.perform(post(path)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VALIDATION"));verify(manager).rollback(any());result(mvc.perform(post(path)));verify(manager).commit(any());}
    @Test void recheckAppendsNewRejectedWithoutChangingOldAndKeepsOriginalFile()throws Exception {String[] row=customer("bad");validate(row);var ledger=org.mockito.ArgumentCaptor.forClass(Ledger.class);verify(mapper).insertLedger(ledger.capture());when(mapper.original(1)).thenReturn(ledger.getValue().original());JsonNode data=result(mvc.perform(post("/api/uploads/1/recheck")));assertThat(data.get("upldId").asText()).isEqualTo("2");assertThat(data.get("fileName").asText()).isEqualTo("거래처.xlsx");assertThat(data.has("token")).isFalse();verify(mapper,times(2)).insertLedger(any());noBusinessWrites();}
    @Test void mappingAddedThenRecheckUsesLatestCodesAndReturnsOnlyToken()throws Exception {String[] row=customer("1234500000");row[4]="핀테크";validate(row);var ledger=org.mockito.ArgumentCaptor.forClass(Ledger.class);verify(mapper).insertLedger(ledger.capture());when(mapper.original(1)).thenReturn(ledger.getValue().original());when(mapper.mappings()).thenReturn(List.of(new Code("INDUSTRY","SI","SI","핀테크")));JsonNode data=result(mvc.perform(post("/api/uploads/1/recheck")));assertThat(data.get("errorRows").asInt()).isZero();assertThat(data.get("warningRows").asInt()).isEqualTo(1);assertThat(data.has("token")).isTrue();assertThat(data.has("upldId")).isFalse();verify(mapper).insertLedger(any());noBusinessWrites();}
    @Test void nonexistentRecheckIs404()throws Exception {
        mvc.perform(post("/api/uploads/99/recheck")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));noBusinessWrites();
        verify(mapper,never()).insertLedger(any());
    }
    @Test void nonexistentErrorFileIs404WithoutDownloadAudit()throws Exception {
        mvc.perform(get("/api/uploads/99/error-file")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        noBusinessWrites();verify(mapper,never()).insertLedger(any());
    }
    @Test void missingStoredFileIs404WithReuploadAdvice()throws Exception {when(mapper.original(1)).thenReturn(new Original("a.xlsx","/missing/private/path"));mvc.perform(post("/api/uploads/1/recheck")).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("원본 파일이 없습니다. 파일을 다시 올려 주세요."));}
    @Test void publicErrorWorkbookPreservesRowsHighlightsAndCanBeCorrectedAndReuploaded()throws Exception {
        String[] row=customer("1234500000");row[1]="";validate(row);var ledger=org.mockito.ArgumentCaptor.forClass(Ledger.class);verify(mapper).insertLedger(ledger.capture());Original original=ledger.getValue().original();when(mapper.original(1)).thenReturn(original);when(mapper.issues(1)).thenReturn(ledger.getValue().result().items());
        var response=mvc.perform(get("/api/uploads/1/error-file")).andExpect(status().isOk()).andReturn().getResponse();assertThat(org.springframework.http.ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename()).isEqualTo("거래처_오류.xlsx");byte[] bytes=response.getContentAsByteArray();assertThat(result(upload(bytes)).get("errorRows").asInt()).isEqualTo(1);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()){assertThat(book.getNumberOfSheets()).isEqualTo(2);assertThat(book.getSheetAt(0).getRow(3).getCell(12).getStringCellValue()).isEqualTo("오류 사유");assertThat(book.getSheetAt(0).getRow(4).getCell(1).getCellStyle().getFillForegroundColor()).isEqualTo(IndexedColors.ROSE.getIndex());assertThat(book.getSheet("오류 목록").getRow(1).getCell(4).getStringCellValue()).contains("상호");book.getSheetAt(0).getRow(4).getCell(1).setCellValue("수정 거래처");book.write(out);JsonNode corrected=result(upload(out.toByteArray()));assertThat(corrected.has("token")).as("corrected upload: %s",corrected).isTrue();}
        verify(mapper).insertAccess(argThat(w->w.type().equals("DOWNLOAD")&&w.fileName().equals("거래처_오류.xlsx")&&w.detail().contains("upldId")&&w.actor()==null));verify(mapper,never()).insertPartner(any());
    }
}
