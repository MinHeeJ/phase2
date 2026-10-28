package com.sicost.dispatchattendance2;

import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.uploadsops.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@WebMvcTest(controllers = {DispatchAttendanceUploadController.class, UploadController.class})
@Import({RequestIdFilter.class, ApiExceptionHandler.class, DispatchAttendanceUploadService.class,
    DispatchAttendanceUploadForm.class, UploadWorkbookWriter.class, UploadInspectionContexts.class,
    PermissionService.class, DispatchAttendanceUploadApiTest.Time.class})
class DispatchAttendanceUploadApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DispatchAttendanceUploadService service;
    @Autowired UploadInspectionContexts contexts;
    @Autowired Clock clock;
    @MockBean DispatchAttendanceUploadMapper mapper;
    @MockBean UploadMapper uploads;
    @MockBean UploadService legacy;
    @MockBean PlatformTransactionManager transactions;
    @TempDir Path directory;
    static final AtomicLong IDS = new AtomicLong(1000);
    static final Scope SCOPE = new Scope(11, "2026-10");
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "directory", directory.toString());
        ReflectionTestUtils.setField(service, "clock", clock);
        when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        when(mapper.nextUploadId()).thenAnswer(i -> IDS.incrementAndGet());
        when(mapper.insertAttendance(any())).thenAnswer(i -> IDS.incrementAndGet());
        when(mapper.project(11)).thenReturn(project("DAY"));
        when(mapper.lockProject(11)).thenReturn(project("DAY"));
        when(mapper.assignments(SCOPE)).thenReturn(List.of(assignment()));
        when(mapper.attendance(SCOPE)).thenReturn(List.of(new Attendance(77L, 99L, 22,
            BigDecimal.ONE, null, BigDecimal.ZERO, new BigDecimal("20"))));
    }
    static Project project(String method) { return new Project(11, "P-2611", "DP", method, "Y"); }
    static Assignment assignment() { return new Assignment(99, 22, "K-0101", "검사 인력", BigDecimal.ONE, 100000L); }
    static Map<String, String> row() {
        var row = new LinkedHashMap<String, String>();
        row.put("연월", "2026-10"); row.put("프로젝트ID", "P-2611"); row.put("관리번호", "K-0101");
        row.put("이름", "검사 인력"); row.put("근무일수", "20"); row.put("정규 근무시간", "160");
        row.put("초과 근무시간", ""); row.put("해당 월 근무일수", "20"); return row;
    }
    static byte[] workbook(List<Map<String, String>> rows) throws Exception {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("근태"); Row title = sheet.createRow(3);
            var headers = DispatchAttendanceUploadForm.HEADERS;
            for (int i = 0; i < headers.size(); i++) title.createCell(i).setCellValue(headers.get(i));
            for (int n = 0; n < rows.size(); n++) {
                Row row = sheet.createRow(n + 5);
                for (int c = 0; c < headers.size(); c++) row.createCell(c).setCellValue(rows.get(n).getOrDefault(headers.get(c), ""));
            }
            book.write(out); return out.toByteArray();
        }
    }
    ResultActions validate(byte[] bytes, String name) throws Exception {
        return mvc.perform(multipart("/api/uploads/dispatch-attendance/validate")
            .file(new MockMultipartFile("file", name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes))
            .param("month", "2026-10").param("projectId", "11"));
    }
    long inspect(Map<String, String> row) throws Exception {
        return json.readTree(validate(workbook(List.of(row)), "근태.xlsx").andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray()).path("data").path("upload_id").asLong();
    }
    ResultActions apply(long id) throws Exception {
        return mvc.perform(post("/api/uploads/dispatch-attendance/apply").contentType("application/json")
            .content(json.writeValueAsString(Map.of("upload_id", id, "confirm", true))));
    }
    void noBusiness() { verify(mapper, never()).deleteAttendance(any()); verify(mapper, never()).insertAttendance(any()); verify(mapper, never()).audit(any()); }

    @Test void anonymousValidationPublishesNumericReviewAndActualRowsWithoutBusinessWrites() throws Exception {
        long id = inspect(row());
        assertThat(contexts.require(id).formType()).isEqualTo("B.3");
        mvc.perform(get("/api/uploads/{id}/rows", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].row").value(6))
            .andExpect(jsonPath("$.data.items[0].attendance.asgn_id").value(99))
            .andExpect(jsonPath("$.data.items[0].attendance.ovtm_tm_qty").value(0))
            .andExpect(jsonPath("$.data.items[0].calculated_mm").value(1.0))
            .andExpect(jsonPath("$.data.items[0].assignment_mm").value(1))
            .andExpect(header().exists("X-Request-Id"));
        noBusiness(); verify(mapper, never()).insertReview(any());
    }
    @Test void anonymousApplyReplacesExactProjectAndMonthAndRecordsOldAndNewHistory() throws Exception {
        long id = inspect(row()); clearInvocations(transactions);
        apply(id).andExpect(status().isOk()).andExpect(jsonPath("$.data.form_type").value("B.3"))
            .andExpect(jsonPath("$.data.upload_id").value(id)).andExpect(jsonPath("$.data.applied_count").value(1));
        var order = inOrder(mapper, uploads);
        order.verify(mapper).lockProject(11);
        order.verify(mapper).insertReview(argThat(w -> w.status().equals("APPLIED") && w.scope().equals(SCOPE)));
        order.verify(mapper).deleteAttendance(SCOPE);
        order.verify(mapper).audit(argThat(a -> a.type().equals("DELETE") && a.before().contains("work_day_qty") && a.after() == null));
        order.verify(mapper).insertAttendance(argThat(w -> w.scope().equals(SCOPE) && w.upload() == id));
        order.verify(mapper).audit(argThat(a -> a.type().equals("INSERT") && a.after().contains("upload_id")));
        order.verify(mapper).access(any());
        verify(transactions).commit(any()); assertThat(contexts.require(id).canApply()).isFalse();
        apply(id).andExpect(status().isOk()); verify(mapper, times(1)).deleteAttendance(SCOPE);
    }
    @ParameterizedTest @CsvSource({"해당 월 근무일수,''", "해당 월 근무일수,0", "해당 월 근무일수,32", "근무일수,''", "근무일수,-1", "정규 근무시간,1.23", "관리번호,lower", "관리번호,UNKNOWN", "이름,다른 인력", "프로젝트ID,OTHER", "연월,2026-11"})
    void rowErrorsRejectEntireWorkbookAndApplyPreservesAttendance(String field, String value) throws Exception {
        var row = row(); row.put(field, value); long id = inspect(row);
        assertThat(contexts.require(id).canApply()).isFalse();
        verify(mapper).insertReview(argThat(w -> w.status().equals("REJECTED")));
        apply(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); noBusiness();
    }
    @Test void duplicateBusinessKeyRejectsWholeFile() throws Exception {
        validate(workbook(List.of(row(), row())), "중복.xlsx").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(false)).andExpect(jsonPath("$.data.error_count").value(1)); noBusiness();
    }
    @Test void validAssignmentRequired() throws Exception {
        when(mapper.assignments(SCOPE)).thenReturn(List.of()); long id = inspect(row());
        apply(id).andExpect(status().isBadRequest()); noBusiness();
    }
    @ParameterizedTest @ValueSource(strings = {"MONTH", "STAGE"})
    void onlyDayAndHourDispatchFormsAreAllowed(String method) throws Exception {
        when(mapper.project(11)).thenReturn(project(method));
        validate(workbook(List.of(row())), "근태.xlsx").andExpect(status().isBadRequest()); noBusiness();
    }
    @Test void nonDispatchProjectRejected() throws Exception {
        when(mapper.project(11)).thenReturn(new Project(11, "P-2611", "MO", "DAY", "Y"));
        validate(workbook(List.of(row())), "근태.xlsx").andExpect(status().isBadRequest()); noBusiness();
    }
    @Test void hourWithoutDaysKeepsNullMmAndRequiresRegularHours() throws Exception {
        when(mapper.project(11)).thenReturn(project("HOUR")); when(mapper.lockProject(11)).thenReturn(project("HOUR"));
        var row = row(); row.put("근무일수", ""); long id = inspect(row);
        mvc.perform(get("/api/uploads/{id}/rows", id)).andExpect(jsonPath("$.data.items[0].calculated_mm").isEmpty());
        apply(id).andExpect(status().isOk());
    }
    @Test void hourRegularHoursMissingIsError() throws Exception {
        when(mapper.project(11)).thenReturn(project("HOUR")); var row = row(); row.put("정규 근무시간", "");
        long id = inspect(row());
        long rejected = inspect(row); apply(rejected).andExpect(status().isBadRequest()); noBusiness();
    }
    @Test void missingPriceIsWarningNotApplyBlocker() throws Exception {
        when(mapper.assignments(SCOPE)).thenReturn(List.of(new Assignment(99, 22, "K-0101", "검사 인력", BigDecimal.ONE, null)));
        long id = inspect(row()); assertThat(contexts.require(id).warningCount()).isEqualTo(1);
        apply(id).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings = {"CLOSED_MONTH", "ATTENDANCE_BILLED"})
    void protectedScopeCannotBeValidated(String code) throws Exception {
        if (code.equals("CLOSED_MONTH")) when(mapper.monthStatus(SCOPE.month())).thenReturn("CLOSED");
        else when(mapper.confirmedUsage(SCOPE)).thenReturn(true);
        validate(workbook(List.of(row())), "근태.xlsx").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code)); noBusiness();
    }
    @ParameterizedTest @ValueSource(strings = {"CLOSED_MONTH", "ATTENDANCE_BILLED"})
    void protectionAddedAfterInspectionRejectsBeforeReplacement(String code) throws Exception {
        long id = inspect(row());
        if (code.equals("CLOSED_MONTH")) when(mapper.monthStatus(SCOPE.month())).thenReturn("CLOSED");
        else when(mapper.confirmedUsage(SCOPE)).thenReturn(true);
        apply(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code)); noBusiness();
    }
    @Test void changedSourceRequiresNewInspection() throws Exception {
        long id = inspect(row()); when(mapper.assignments(SCOPE)).thenReturn(List.of(
            new Assignment(99, 22, "K-0101", "검사 인력", new BigDecimal("0.5"), 100000L)));
        apply(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VALIDATION")); noBusiness();
    }
    @Test void changedStoredWorkbookCannotApplyOldInspection() throws Exception {
        long id = inspect(row()); var changed = row(); changed.put("근무일수", "19");
        try (var paths = Files.list(directory)) { Files.write(paths.findFirst().orElseThrow(), workbook(List.of(changed))); }
        apply(id).andExpect(status().isConflict()); noBusiness();
    }
    @ParameterizedTest @ValueSource(strings = {"review", "insert", "audit", "access"})
    void anyWriteFailureRollsBackTransactionAndKeepsReviewApplicable(String failure) throws Exception {
        long id = inspect(row()); clearInvocations(transactions);
        switch (failure) {
            case "review" -> doThrow(new IllegalStateException()).when(mapper).insertReview(any());
            case "insert" -> when(mapper.insertAttendance(any())).thenThrow(new IllegalStateException());
            case "audit" -> doThrow(new IllegalStateException()).when(mapper).audit(any());
            default -> doThrow(new IllegalStateException()).when(mapper).access(any());
        }
        apply(id).andExpect(status().isInternalServerError());
        verify(transactions).rollback(any()); verify(transactions, never()).commit(any());
        assertThat(contexts.require(id).canApply()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"upload_id\":1,\"confirm\":false}", "{\"upload_id\":1.2,\"confirm\":true}", "{\"upload_id\":-1,\"confirm\":true}"})
    void applyRequiresExplicitTrueAndNumericInspectionId(String body) throws Exception {
        mvc.perform(post("/api/uploads/dispatch-attendance/apply").contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); noBusiness();
    }
    @Test void unknownInspectionCannotApply() throws Exception {
        apply(9999999).andExpect(status().isGone()); noBusiness();
    }
    @Test void inspectionExpiresAfterThirtyMinutes() throws Exception {
        long id = inspect(row());
        ReflectionTestUtils.setField(service, "clock", Clock.offset(clock, Duration.ofMinutes(30)));
        apply(id).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("VALIDATION_EXPIRED")); noBusiness();
    }
    @Test void tooLargeFileIsValidationError() throws Exception {
        validate(new byte[10 * 1024 * 1024 + 1], "대용량.xlsx").andExpect(status().isBadRequest()); noBusiness();
    }
    @Test void tooManyRowsIsFileError() throws Exception {
        validate(workbook(Collections.nCopies(10001, row())), "행초과.xlsx").andExpect(status().isBadRequest()); noBusiness();
    }
    @Test void malformedXlsxRejected() throws Exception {
        validate("not a workbook".getBytes(), "잘못.xlsx").andExpect(status().isBadRequest()); noBusiness();
    }
    @Test void renamedFileTypeRejected() throws Exception {
        validate(workbook(List.of(row())), "근태.csv").andExpect(status().isBadRequest()); noBusiness();
    }
    @Test void missingHeaderRejected() throws Exception {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            book.createSheet("근태").createRow(0).createCell(0).setCellValue("이름"); book.write(out);
            validate(out.toByteArray(), "제목없음.xlsx").andExpect(status().isBadRequest()); noBusiness();
        }
    }
    @Test void formulaIsNeverEvaluatedAndErrorsWorkbookKeepsActualLine() throws Exception {
        byte[] bytes = workbook(List.of(row()));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes)); var out = new ByteArrayOutputStream()) {
            book.getSheetAt(0).getRow(5).getCell(4).setCellFormula("1+1"); book.write(out);
            var response = validate(out.toByteArray(), "수식.xlsx").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_apply").value(false)).andReturn().getResponse();
            long id = json.readTree(response.getContentAsByteArray()).path("data").path("upload_id").asLong();
            var file = mvc.perform(get("/api/uploads/{id}/error-file", id)).andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn().getResponse();
            try (var errors = new XSSFWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
                assertThat(errors.getSheet("오류 목록").getRow(1).getCell(1).getNumericCellValue()).isEqualTo(6);
            }
        }
        noBusiness();
    }
    @Test void issueFiltersAndCountsUseSamePredicate() throws Exception {
        var bad = row(); bad.put("해당 월 근무일수", ""); long id = inspect(bad);
        mvc.perform(get("/api/uploads/{id}/issues", id).param("column", "해당 월 근무일수").param("severity", "ERROR"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].row").value(6));
        mvc.perform(get("/api/uploads/{id}/issues", id).param("severity", "WARNING"))
            .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.items").isEmpty());
    }
}
