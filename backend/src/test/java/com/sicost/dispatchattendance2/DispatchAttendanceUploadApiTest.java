package com.sicost.dispatchattendance2;

import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sicost.apiops.*;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import com.sicost.uploadsops.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@WebMvcTest(controllers={DispatchAttendanceUploadController.class,UploadController.class,UploadResultController.class})
@Import({RequestIdFilter.class,ApiExceptionHandler.class,DispatchAttendanceUploadAdvice.class,
    DispatchAttendanceUploadService.class,DispatchAttendanceUploadForm.class,UploadService.class,
    PartnerUploadForm.class,UploadWorkbookWriter.class,UploadInspectionContexts.class,UploadResultService.class,
    AssignmentConcurrencyService.class,CurrentUser.class,PermissionService.class,DispatchAttendanceUploadApiTest.Time.class})
@TestPropertySource(properties="sicost.upload-directory=${java.io.tmpdir}/sicost/attendance-tests")
class DispatchAttendanceUploadApiTest {
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-22T00:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @TestConfiguration static class Time { @Bean MutableClock clock(){return new MutableClock();} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired DispatchAttendanceUploadService service;
    @MockBean DispatchAttendanceUploadMapper mapper;
    @MockBean UploadMapper legacy;
    @MockBean PersonnelUploadMapper allocation;
    @MockBean UploadResultMapper resultMapper;
    @MockBean AssignmentConcurrencyMapper locks;
    @MockBean PlatformTransactionManager transactions;
    static final AtomicLong IDS=new AtomicLong(3000);
    static final String BASE="/api/uploads/dispatch-attendance";
    @BeforeAll static void durableContractDeclaresUploadOperations() throws Exception {
        com.sicost.support.OpenApiFixture.assertOperation("/api/uploads/dispatch-attendance/validate", "post", "validateDispatchAttendanceUpload");
        com.sicost.support.OpenApiFixture.assertOperation("/api/uploads/dispatch-attendance/apply", "post", "applyDispatchAttendanceUpload");
    }
    @BeforeEach void setup() {
        clock.now=Instant.parse("2026-10-22T00:00:00Z");
        when(mapper.project(1)).thenReturn(new Project(1,"DP","DAY","Y"));
        when(mapper.sources(any())).thenReturn(List.of(source()));
        when(mapper.lockAttendance(any())).thenReturn(List.of(old()));
        when(mapper.insertAttendance(any())).thenReturn(42L);
        when(locks.lockProject(1)).thenReturn(1L);
        when(allocation.nextUploadId()).thenAnswer(i->IDS.incrementAndGet());
        when(transactions.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
    }
    static Source source(){return new Source(11,21,"K-0021","검사 인력",new BigDecimal("1.00"),true);}
    static Attendance old(){return new Attendance(40L,"2026-10",1,21,11L,new BigDecimal("5.0"),null,
        BigDecimal.ZERO,new BigDecimal("20.0"),null);}
    static Map<String,String> row() {
        return new LinkedHashMap<>(Map.of("연월","2026-10","프로젝트ID","1","관리번호","K-0021",
            "이름","검사 인력","근무일수","20","정규 근무시간","160","초과 근무시간","","해당 월 근무일수","20"));
    }
    static byte[] workbook(List<Map<String,String>> rows) throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            Sheet sheet=book.createSheet("근태");
            Row header=sheet.createRow(3);
            var headers=PersonnelUploadForm.ATTENDANCE_HEADERS;
            for(int i=0;i<headers.size();i++) header.createCell(i).setCellValue(headers.get(i));
            for(int n=0;n<rows.size();n++) {
                Row row=sheet.createRow(n+5);
                for(int i=0;i<headers.size();i++) row.createCell(i).setCellValue(rows.get(n).getOrDefault(headers.get(i),""));
            }
            book.write(out);return out.toByteArray();
        }
    }
    ResultActions validate(byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/uploads/dispatch-attendance/validate").file(new MockMultipartFile("file","근태.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)).param("month","2026-10").param("projectId","1"));
    }
    long inspect(Map<String,String> row) throws Exception {
        return json.readTree(validate(workbook(List.of(row))).andExpect(status().isOk()).andReturn()
            .getResponse().getContentAsByteArray()).path("data").path("upload_id").asLong();
    }
    ResultActions apply(long id) throws Exception {
        return mvc.perform(post("/api/uploads/dispatch-attendance/apply").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("upload_id",id,"confirm",true))));
    }
    void noBusiness() {
        verify(mapper,never()).deleteAttendance(any());
        verify(mapper,never()).insertAttendance(any());
        verify(mapper,never()).audit(any());
    }
    @Test void anonymousValidationUsesCommonResultAndRealRowNumbersWithoutBusinessWrites() throws Exception {
        var validated=mvc.perform(multipart("/api/uploads/dispatch-attendance/validate")
            .file(new MockMultipartFile("file", "근태.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook(List.of(row()))))
            .param("month", "2026-10").param("projectId", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.form_type").value("B.3"))
            .andExpect(jsonPath("$.data.row_count").value(1))
            .andExpect(jsonPath("$.data.error_count").value(0))
            .andExpect(jsonPath("$.data.can_apply").value(true)).andReturn();
        long id=json.readTree(validated.getResponse().getContentAsByteArray()).path("data").path("upload_id").asLong();
        assertThat(id).isPositive();
        mvc.perform(get("/api/uploads/{id}",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.form_type").value("B.3")).andExpect(jsonPath("$.data.can_apply").value(true))
            .andExpect(jsonPath("$.data.overwrite_notice").isNotEmpty());
        mvc.perform(get("/api/uploads/{id}/rows",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].row").value(6))
            .andExpect(jsonPath("$.data.items[0].attendance.asgn_id").value(11))
            .andExpect(jsonPath("$.data.items[0].attendance.calculated_mm").value(1))
            .andExpect(jsonPath("$.data.items[0].attendance.ovtm_tm_qty").value(0));
        noBusiness(); verify(mapper,never()).insertUpload(any()); verifyNoInteractions(resultMapper);
    }
    @Test void anonymousApplyReplacesWholeSelectedScopeAndAuditsBeforeAfterInsideTransaction() throws Exception {
        long id=inspect(row());
        mvc.perform(post("/api/uploads/dispatch-attendance/apply").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("upload_id", id, "confirm", true))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.upload_id").value(id))
            .andExpect(jsonPath("$.data.form_type").value("B.3")).andExpect(jsonPath("$.data.applied_count").value(1));
        var order=inOrder(locks,mapper,transactions);
        order.verify(locks).lockProject(1);
        order.verify(mapper).lockAttendance(any());
        order.verify(mapper).confirmedUsage(any());
        order.verify(mapper).insertUpload(argThat(w->w.status().equals("APPLIED") && w.actor()==null));
        order.verify(mapper).deleteAttendance(argThat(s->s.projectId()==1 && s.month().equals("2026-10")));
        order.verify(mapper).insertAttendance(any());
        order.verify(mapper).audit(argThat(a->a.before().contains("5.0") && a.after().contains("20.0") && a.actor()==null));
        order.verify(transactions).commit(any());
        mvc.perform(get("/api/uploads/{id}",id)).andExpect(jsonPath("$.data.can_apply").value(false));
        apply(id).andExpect(status().isOk()); verify(mapper,times(1)).deleteAttendance(any());
    }
    @ParameterizedTest @CsvSource({"해당 월 근무일수,''","해당 월 근무일수,0","해당 월 근무일수,32","관리번호,WRONG","관리번호,k-0021","이름,다른 이름","근무일수,-1","근무일수,1.01","근무일수,''","정규 근무시간,-2","연월,2026-09","프로젝트ID,2"})
    void rowRulesRejectEntireFile(String column,String value) throws Exception {
        var row=row();row.put(column,value);long id=inspect(row);
        assertThat(service.result(id).can_apply()).isFalse();
        mvc.perform(get("/api/uploads/{id}/issues",id).param("column",column))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].row").value(6));
        apply(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper).insertUpload(argThat(w->w.status().equals("REJECTED"))); noBusiness();
    }
    @Test void duplicateDeveloperRejectsFileWithoutPartialApply() throws Exception {
        long id=json.readTree(validate(workbook(List.of(row(),row()))).andExpect(status().isOk()).andReturn()
            .getResponse().getContentAsByteArray()).path("data").path("upload_id").asLong();
        assertThat(service.result(id).error_count()).isEqualTo(1);
        apply(id).andExpect(status().isBadRequest());noBusiness();
    }
    @Test void hourWithoutWorkDaysAllowsNullMmAndZeroOvertimeWithWarning() throws Exception {
        when(mapper.project(1)).thenReturn(new Project(1,"DP","HOUR","Y"));
        var row=row();row.put("근무일수","");long id=inspect(row);
        assertThat(service.result(id).can_apply()).isTrue();
        mvc.perform(get("/api/uploads/{id}/rows",id)).andExpect(jsonPath("$.data.items[0].attendance.calculated_mm").isEmpty());
        apply(id).andExpect(status().isOk());
        verify(mapper).insertAttendance(argThat(w->w.row().work_day_qty()==null && w.row().ovtm_tm_qty().signum()==0));
    }
    @Test void hourRequiresRegularHours() throws Exception {
        when(mapper.project(1)).thenReturn(new Project(1,"DP","HOUR","Y"));
        var row=row();row.put("정규 근무시간","");long id=inspect(row);
        apply(id).andExpect(status().isBadRequest());noBusiness();
    }
    @Test void zeroAttendanceAndMissingPriceAreAllowedWithoutChangingSourceMm() throws Exception {
        var s=source();when(mapper.sources(any())).thenReturn(List.of(new Source(s.asgn_id(),s.dev_id(),s.mng_no(),s.dev_nm(),s.assignment_mm(),false)));
        var row=row();row.put("근무일수","0");long id=inspect(row);
        assertThat(service.result(id).can_apply()).isTrue();
        apply(id).andExpect(status().isOk());verify(mapper).insertAttendance(argThat(w->w.row().work_day_qty().signum()==0));
    }
    @Test void noEffectiveAssignmentRejects() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of());long id=inspect(row());apply(id).andExpect(status().isBadRequest());noBusiness();
    }
    @ParameterizedTest @CsvSource({"TK,DAY","DP,MONTH","MO,HOUR"})
    void unsupportedProjectOrSettlementRefused(String type,String settlement) throws Exception {
        when(mapper.project(1)).thenReturn(new Project(1,type,settlement,"Y"));
        validate(workbook(List.of(row()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));noBusiness();
    }
    @Test void missingProjectIs404() throws Exception {
        when(mapper.project(1)).thenReturn(null);validate(workbook(List.of(row()))).andExpect(status().isNotFound());noBusiness();
    }
    @Test void closedAtValidationIs409() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        validate(workbook(List.of(row()))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));noBusiness();
    }
    @Test void confirmedUsageAtValidationIs409() throws Exception {
        when(mapper.confirmedUsage(any())).thenReturn(true);
        validate(workbook(List.of(row()))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_BILLED"));noBusiness();
    }
    @Test void closeAfterInspectionPreservesAttendance() throws Exception {
        long id=inspect(row());when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        apply(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));noBusiness();
    }
    @Test void confirmationAfterInspectionPreservesAttendance() throws Exception {
        long id=inspect(row());when(mapper.confirmedUsage(any())).thenReturn(true);
        apply(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_BILLED"));noBusiness();
    }
    @Test void changedInspectionSourceRequiresFreshReview() throws Exception {
        long id=inspect(row());var s=source();when(mapper.sources(any())).thenReturn(List.of(new Source(12,s.dev_id(),s.mng_no(),s.dev_nm(),s.assignment_mm(),true)));
        apply(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VALIDATION"));noBusiness();
    }
    @ParameterizedTest @ValueSource(strings={"upload","attendance","audit","access"})
    void anyWriteFailureRollsBackTransactionAndRetainsReview(String failure) throws Exception {
        long id=inspect(row());
        switch(failure) {
            case "upload" -> doThrow(new IllegalStateException("write")).when(mapper).insertUpload(any());
            case "attendance" -> when(mapper.insertAttendance(any())).thenThrow(new IllegalStateException("write"));
            case "audit" -> doThrow(new IllegalStateException("write")).when(mapper).audit(any());
            case "access" -> doThrow(new IllegalStateException("write")).when(mapper).access(any());
        }
        apply(id).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SERVER_ERROR"));
        verify(transactions).rollback(any());verify(transactions,never()).commit(any());assertThat(service.result(id).can_apply()).isTrue();
    }
    @Test void recheckAllocatesNewIdAndRetiresOldApplicability() throws Exception {
        long id=inspect(row());
        var response=mvc.perform(post("/api/uploads/{id}/revalidate",id)).andExpect(status().isOk()).andReturn();
        long next=json.readTree(response.getResponse().getContentAsByteArray()).path("data").path("upload_id").asLong();
        assertThat(next).isNotEqualTo(id);assertThat(service.result(id).can_apply()).isFalse();
        apply(id).andExpect(status().isConflict());apply(next).andExpect(status().isOk());
    }
    @Test void expiredInspectionReturns410() throws Exception {
        long id=inspect(row());clock.now=clock.now.plusSeconds(1800);
        apply(id).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("VALIDATION_EXPIRED"));noBusiness();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"upload_id\":1}","{\"upload_id\":1,\"confirm\":false}","{\"upload_id\":1.5,\"confirm\":true}","{\"upload_id\":0,\"confirm\":true}","{broken"})
    void invalidApplyWireIs400(String input) throws Exception {
        mvc.perform(post("/api/uploads/dispatch-attendance/apply").contentType(MediaType.APPLICATION_JSON).content(input))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());noBusiness();
    }
    @Test void genericApplyCannotBypassExplicitConfirmation() throws Exception {
        long id=inspect(row());mvc.perform(post("/api/uploads/{id}/apply",id)).andExpect(status().isBadRequest());noBusiness();
    }
    @Test void transportMissingFileAndBadProjectUseFeatureValidationEnvelope() throws Exception {
        mvc.perform(multipart("/api/uploads/dispatch-attendance/validate").param("month","2026-10").param("projectId","1"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(multipart(BASE+"/validate").file(new MockMultipartFile("file","a.xlsx","application/octet-stream",workbook(List.of(row()))))
            .param("month","2026-10").param("projectId","oops"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));noBusiness();
    }
    @Test void malformedWorkbookRefused() throws Exception {
        validate("not a workbook".getBytes()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));noBusiness();
    }
    @Test void renamedFileExtensionRefused() throws Exception {
        mvc.perform(multipart(BASE+"/validate").file(new MockMultipartFile("file","wrong.csv","application/octet-stream",workbook(List.of(row()))))
            .param("month","2026-10").param("projectId","1")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));noBusiness();
    }
    @Test void tenMegabyteLimitPreserves413() throws Exception {
        validate(new byte[10*1024*1024+1]).andExpect(status().isPayloadTooLarge());noBusiness();
    }
    @Test void rowLimitRefused() throws Exception {
        validate(workbook(Collections.nCopies(10001,row()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"));noBusiness();
    }
    @Test void headerMissingRefused() throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(workbook(List.of(row()))));var out=new ByteArrayOutputStream()) {
            book.getSheetAt(0).getRow(3).removeCell(book.getSheetAt(0).getRow(3).getCell(7));book.write(out);
            validate(out.toByteArray()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUIRED_COLUMN_MISSING"));
        }noBusiness();
    }
    @Test void missingTitleRefused() throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            book.createSheet("근태").createRow(0).createCell(0).setCellValue("제목 없음");book.write(out);
            validate(out.toByteArray()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HEADER_NOT_FOUND"));
        }noBusiness();
    }
    @Test void formulaIsNeverEvaluatedAndErrorFileIsRealXlsxWithActualRow() throws Exception {
        byte[] bytes;
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(workbook(List.of(row()))));var out=new ByteArrayOutputStream()) {
            book.getSheetAt(0).getRow(5).getCell(4).setCellFormula("1+1");book.write(out);bytes=out.toByteArray();
        }
        long id=json.readTree(validate(bytes).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data").path("upload_id").asLong();
        apply(id).andExpect(status().isBadRequest());
        var response=mvc.perform(get("/api/uploads/{id}/errors",id)).andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheet("오류 목록").getRow(1).getCell(1).getNumericCellValue()).isEqualTo(6);
            assertThat(book.getSheet("오류 목록").getRow(1).getCell(4).getStringCellValue()).contains("수식");
        }noBusiness();
    }
    @Test void externalWorkbookLinkRefused() throws Exception {
        try(var other=new XSSFWorkbook();var book=new XSSFWorkbook(new ByteArrayInputStream(workbook(List.of(row()))));var out=new ByteArrayOutputStream()) {
            other.createSheet("외부");book.linkExternalWorkbook("external.xlsx",other);book.write(out);
            validate(out.toByteArray()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
        }noBusiness();
    }
    @Test void templateHasEightHeadersAndNoBusinessExamples() throws Exception {
        var response=mvc.perform(get("/api/uploads/templates/B.3")).andExpect(status().isOk()).andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(3).getLastCellNum()).isEqualTo((short)8);
            assertThat(book.getSheetAt(0).getRow(3).getCell(7).getStringCellValue()).isEqualTo("해당 월 근무일수");
            assertThat((Object)book.getSheetAt(0).getRow(4)).isNull();
        }
    }
}
