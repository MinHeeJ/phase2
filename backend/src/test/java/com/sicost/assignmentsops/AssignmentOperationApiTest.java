package com.sicost.assignmentsops;

import static com.sicost.assignmentsops.AssignmentOperationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@WebMvcTest(controllers = AssignmentOperationController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, AssignmentOperationService.class,
    AssignmentExportService.class, ExcelExportService.class, CurrentUser.class, PermissionService.class,
    AssignmentOperationApiTest.Time.class})
class AssignmentOperationApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AssignmentOperationMapper mapper;
    @MockBean AssignmentConcurrencyService concurrency;
    @MockBean PlatformTransactionManager manager;
    static final LocalDateTime VERSION = LocalDateTime.parse("2026-10-01T00:00:00.123456");
    Row row;
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        row = row(1, "M", "ACTIVE");
        when(mapper.row(any())).thenAnswer(i -> row);
        when(mapper.project(anyLong())).thenReturn(new Project(1, "DP", "PLAN", "Y"));
        when(mapper.developer(anyLong())).thenReturn(new Developer(1, "FL", "Y"));
        when(mapper.activeCode(any())).thenReturn(true);
        when(mapper.closedMonths()).thenReturn(List.of("2026-09"));
        when(mapper.personTotal(any())).thenReturn(BigDecimal.ONE);
        when(mapper.months(anyLong())).thenReturn(new ArrayList<>());
        when(mapper.rates(anyLong())).thenReturn(new ArrayList<>());
        when(mapper.changes(anyLong())).thenReturn(new ArrayList<>());
        when(mapper.onLeave(any())).thenReturn(true);
        when(mapper.codeName(any())).thenAnswer(i -> "H".equals(i.getArgument(0, CodeKey.class).code()) ? "높음" : "보통");
        when(concurrency.lockAndCompare(anyLong(), any())).thenReturn(
            new AssignmentConcurrencyMapper.AssignmentLock(1, VERSION, VERSION.plusDays(1)));
        when(concurrency.advance(any())).thenReturn(VERSION.plusDays(1));
    }
    static Row row(long id, String chance, String status) {
        return new Row(id, "A-0001", 1, "프로젝트", 1, "나가는 사람", "K-0001", "BE",
            LocalDate.of(2026,10,1), LocalDate.of(2026,12,31), LocalDate.of(2026,12,31),
            BigDecimal.ONE, "NEW", "MONTH", chance, null, null, status, null, 7000000L,
            BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("3.00"), VERSION, null);
    }
    Map<String,Object> proposal() {
        var input = new LinkedHashMap<String,Object>();
        input.put("prj_id",1); input.put("dev_id",1); input.put("job_cd","BE");
        input.put("bgn_dt","2026-10-16"); input.put("end_dt","2026-10-31");
        input.put("asgn_rt",1); input.put("asgn_se_cd","NEW");
        input.put("bill_untprc_amt",7000000); input.put("untprc_base_cd","MONTH");
        return input;
    }
    Map<String,Object> batch(String value) {
        var input = new LinkedHashMap<String,Object>();
        input.put("ids",List.of(1)); input.put("versions",List.of(Map.of("asgn_id",1,"updated_at",VERSION.toString())));
        if (value != null) input.put("value",value);
        return input;
    }
    Map<String,Object> replacement() {
        return new LinkedHashMap<>(Map.of("updated_at",VERSION.toString(),"aply_dt","2026-11-16",
            "dev_id",2,"rsn_cd","PERSON","rsn_desc","교체"));
    }
    ResultActions postBody(String path, Object value) throws Exception {
        return mvc.perform(post(path).contentType("application/json").content(json.writeValueAsString(value)));
    }
    @Test void previewAnonymousFourWarningsAndHalfMonthWithoutBusinessSideEffects() throws Exception {
        mvc.perform(post("/api/assignments/check").contentType("application/json").content(json.writeValueAsString(proposal()))).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.warnings.length()").value(4))
            .andExpect(jsonPath("$.data.months[0].auto_mm_qty").value(0.52))
            .andExpect(jsonPath("$.data.months[0].person_month_mm_qty").value(1.52));
        verify(mapper, never()).nextId(); verify(mapper, never()).insertHistory(any());
        verify(mapper, never()).insertReplacement(any()); verify(mapper, never()).saveMonth(any());
        verify(mapper, never()).insertRate(any());
    }
    @ParameterizedTest @ValueSource(strings={"0","1.01","-0.1"})
    void previewRejectsRate(String rate) throws Exception {
        var input=proposal(); input.put("asgn_rt",new BigDecimal(rate));
        postBody("/api/assignments/check",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("asgn_rt"));
    }
    @Test void previewRejectsEndBeforeStart() throws Exception {
        var input=proposal(); input.put("end_dt","2026-10-15");
        mvc.perform(post("/api/assignments/check").contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("end_dt"));
    }
    @Test void previewPastStartRequiresReason() throws Exception {
        var input=proposal(); input.put("bgn_dt","2026-09-01");
        postBody("/api/assignments/check",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rsn_cd"));
    }
    @Test void closedPreviewReturnsNoticeButNoClosedMonth() throws Exception {
        var input=proposal(); input.put("bgn_dt","2026-09-01"); input.put("rsn_cd","PERSON");
        postBody("/api/assignments/check",input).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.warnings[0].code").value("CLOSED_MONTH"))
            .andExpect(jsonPath("$.data.months[0].work_ym").value("2026-10"));
    }
    @Test void exactlyOneHasNoOverMmWarning() throws Exception {
        when(mapper.personTotal(any())).thenReturn(new BigDecimal("0.48"));
        postBody("/api/assignments/check",proposal()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.warnings.length()").value(3));
    }
    @Test void anonymousChanceSideEffectWritesHistoryWithReadableNames() throws Exception {
        mvc.perform(post("/api/assignments/ext-chance").contentType("application/json").content(json.writeValueAsString(batch("H")))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].status").value("APPLIED"));
        verify(mapper).chance(any());
        verify(mapper).insertHistory(argThat(h -> h.type().equals("EXT_CHANCE") && h.before().equals("보통")
            && h.after().equals("높음") && h.date().equals(LocalDate.of(2026,10,14))));
    }
    @Test void businessEqualChanceSkipsWithoutHistoryOrTimestamp() throws Exception {
        row=row(1,"H","ACTIVE");
        mvc.perform(post("/api/assignments/ext-chance").contentType("application/json").content(json.writeValueAsString(batch("H")))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].status").value("SKIPPED"));
        verify(mapper,never()).chance(any()); verify(mapper,never()).insertHistory(any()); verify(concurrency,never()).advance(any());
    }
    @ParameterizedTest @ValueSource(strings={"X","", "HIGH"})
    void invalidChanceIsFieldError(String value) throws Exception {
        mvc.perform(post("/api/assignments/ext-chance").contentType("application/json").content(json.writeValueAsString(batch(value)))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("value"));
    }
    @Test void batchRequiresUniqueIdenticalVersionSet() throws Exception {
        var input=batch("H"); input.put("versions",List.of(Map.of("asgn_id",2,"updated_at",VERSION.toString())));
        postBody("/api/assignments/ext-chance",input).andExpect(status().isBadRequest());
        input=batch(null); input.put("ids",List.of(1,1));
        postBody("/api/assignments/cancel",input).andExpect(status().isBadRequest());
    }
    @Test void missingVersionsRejected() throws Exception {
        mvc.perform(post("/api/assignments/cancel").contentType("application/json").content(json.writeValueAsString(Map.of("ids",List.of(1))))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("versions"));
    }
    @Test void chanceStaleExcludedNotPermissionDenied() throws Exception {
        when(concurrency.lockAndCompare(anyLong(),any())).thenThrow(com.sicost.common.validation.AssignmentRules.stale());
        postBody("/api/assignments/ext-chance",batch("H")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].status").value("EXCLUDED"))
            .andExpect(jsonPath("$.data.items[0].code").value("STALE_ASSIGNMENT"));
        verify(mapper,never()).chance(any());
    }
    @Test void anonymousCancelDeletesOpenMonthsNeverHistory() throws Exception {
        mvc.perform(post("/api/assignments/cancel").contentType("application/json").content(json.writeValueAsString(batch(null)))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].status").value("APPLIED"));
        verify(mapper).cancel(any()); verify(mapper).deleteOpen(1);
        verify(mapper,never()).insertHistory(any());
    }
    @Test void cancellationInUseConflictHasNoWrite() throws Exception {
        when(mapper.inUse(1)).thenReturn("청구 상세 연결 이력이 있습니다.");
        postBody("/api/assignments/cancel",batch(null)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ASSIGNMENT_IN_USE"));
        verify(mapper,never()).cancel(any());
    }
    @Test void cancellationStaleConflictHasNoWrite() throws Exception {
        when(concurrency.lockAndCompare(anyLong(),any())).thenThrow(com.sicost.common.validation.AssignmentRules.stale());
        postBody("/api/assignments/cancel",batch(null)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STALE_ASSIGNMENT"));
        verify(mapper,never()).cancel(any());
    }
    @Test void replacedCannotCancelOrReplaceOrChangeChance() throws Exception {
        row=row(1,"M","REPLACED");
        postBody("/api/assignments/cancel",batch(null)).andExpect(status().isConflict());
        postBody("/api/assignments/1/replace",replacement()).andExpect(status().isConflict());
        postBody("/api/assignments/ext-chance",batch("H")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].status").value("EXCLUDED"));
        verify(mapper,never()).insertReplacement(any()); verify(mapper,never()).chance(any());
    }
    @ParameterizedTest @ValueSource(strings={"2026-10-01","2027-01-01"})
    void replacementDateBounds(String date) throws Exception {
        var input=replacement(); input.put("aply_dt",date);
        postBody("/api/assignments/1/replace",input).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("교체일은 투입일 다음 날부터 종료일까지입니다."));
    }
    @Test void replacementSamePersonRejected() throws Exception {
        var input=replacement(); input.put("dev_id",1);
        mvc.perform(post("/api/assignments/1/replace").contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("dev_id"));
    }
    @Test void replacementMissingReasonRejected() throws Exception {
        var input=replacement(); input.remove("rsn_cd");
        postBody("/api/assignments/1/replace",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rsn_cd"));
    }
    @Test void replacementOverlapNoWrites() throws Exception {
        when(mapper.overlaps(any())).thenReturn(true);
        mvc.perform(post("/api/assignments/1/replace").contentType("application/json").content(json.writeValueAsString(replacement()))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("OVERLAP_ASSIGNMENT"));
        verify(mapper,never()).shorten(any()); verify(mapper,never()).nextId();
    }
    @Test void closedProjectReplacementConflict() throws Exception {
        when(mapper.project(1)).thenReturn(new Project(1,"DP","END","Y"));
        postBody("/api/assignments/1/replace",replacement()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));
    }
    @Test void anonymousReplacementReturnsConcreteFlattenedDetail() throws Exception {
        Row original=row;
        var incoming=new java.util.concurrent.atomic.AtomicReference<Row>();
        when(mapper.nextId()).thenReturn(2L);
        when(mapper.rates(1)).thenReturn(List.of(new Rate(LocalDate.of(2026,10,1),7000000L)));
        when(mapper.row(any())).thenAnswer(i -> i.getArgument(0,ReadKey.class).id()==1 ? row : incoming.get());
        doAnswer(i -> {
            var write=i.getArgument(0,EndWrite.class);
            row=replacementRow(original,1,1,original.bgn_dt(),write.end(),"REPLACED");
            return null;
        }).when(mapper).shorten(any());
        doAnswer(i -> {
            var write=i.getArgument(0,NewAssignment.class);
            incoming.set(replacementRow(original,write.id(),write.request().dev_id(),write.request().aply_dt(),original.end_dt(),"ACTIVE"));
            return null;
        }).when(mapper).insertReplacement(any());
        mvc.perform(post("/api/assignments/1/replace").contentType("application/json").content(json.writeValueAsString(replacement()))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.new_assignment.asgn_id").value(2))
            .andExpect(jsonPath("$.data.new_assignment.dev_id").value(2))
            .andExpect(jsonPath("$.data.new_assignment.asgn_stts_cd").value("ACTIVE"))
            .andExpect(jsonPath("$.data.new_assignment.asgn_se_cd").value("SUB"))
            .andExpect(jsonPath("$.data.new_assignment.cntrct_dev_id").value(1))
            .andExpect(jsonPath("$.data.new_assignment.bgn_dt").value("2026-11-16"))
            .andExpect(jsonPath("$.data.previous_assignment.asgn_stts_cd").value("REPLACED"))
            .andExpect(jsonPath("$.data.previous_assignment.end_dt").value("2026-11-15"))
            .andExpect(jsonPath("$.data.previous_assignment.updated_at").isString());
        verify(mapper).insertReplacement(argThat(w -> w.id()==2 && w.original().dev_id()==1));
        verify(mapper).insertRate(argThat(w -> w.id()==2&&w.amount()==7000000L&&w.date().equals(LocalDate.of(2026,11,16))));
        verify(mapper).insertHistory(argThat(h -> h.id()==1&&h.type().equals("REPLACE")&&h.linked()==2
            &&h.before().equals(original.dev_nm())&&h.after().contains("2026-11-16~2026-12-31")));
        verify(mapper).insertHistory(argThat(h -> h.id()==2&&h.type().equals("START")&&h.before()==null
            &&h.after().equals("2026-11-16~2026-12-31")));
        verify(mapper,never()).saveMonth(argThat(w -> w.month().equals("2026-09")));
    }
    private Row replacementRow(Row original,long id,long developer,LocalDate start,LocalDate end,String status) {
        boolean incoming=id!=original.asgn_id();
        return new Row(id,incoming?"A-0002":original.asgn_no(),original.prj_id(),original.prj_nm(),developer,
            incoming?"들어오는 사람":original.dev_nm(),incoming?"K-0002":original.mng_no(),original.job_cd(),start,end,
            original.plan_end_dt(),original.asgn_rt(),incoming?"SUB":original.asgn_se_cd(),original.untprc_base_cd(),
            original.ext_psblty_cd(),incoming?Long.valueOf(original.dev_id()):original.cntrct_dev_id(),null,status,
            incoming?original.asgn_id():null,original.bill_untprc_amt(),original.current_month_mm_qty(),
            original.person_month_mm_qty(),original.year_mm_qty(),VERSION.plusDays(1),null);
    }
    @Test void anonymousExportRealXlsxWithoutBusinessWriteSideEffects() throws Exception {
        when(mapper.exportRows(any())).thenReturn(List.of(row));
        var result=mvc.perform(get("/api/assignments/export").param("year","2026").param("job","BE"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn();
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("2026_20261014.xlsx");
        try (var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(4).getCell(0).getStringCellValue()).isEqualTo("A-0001");
        }
        verify(mapper,never()).insertHistory(any()); verify(mapper,never()).nextId();
    }
    @ParameterizedTest @ValueSource(strings={"bad","0","10000"})
    void invalidExportYear(String year) throws Exception {
        mvc.perform(get("/api/assignments/export").param("year",year)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("year"));
    }
    @Test void exportRejectsSqlSortInjection() throws Exception {
        mvc.perform(get("/api/assignments/export").param("year","2026").param("sort","prj_nm;DROP TABLE"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sort"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"asgn_no", "prj_nm", "dev_nm", "job_cd", "bgn_dt", "end_dt",
        "bill_untprc_amt", "current_month_mm_qty", "year_mm_qty", "ext_psblty_cd", "asgn_se_cd"})
    void exportAcceptsEveryListSortColumn(String column) throws Exception {
        when(mapper.exportRows(any())).thenReturn(List.of(row));
        mvc.perform(get("/api/assignments/export").param("year", "2026").param("sort", column + ",asc"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/assignments/export").param("year", "2026").param("sort", column + ",desc"))
            .andExpect(status().isOk());
    }
    @Test void exportRejectsTrailingEmptySortLikeList() throws Exception {
        mvc.perform(get("/api/assignments/export").param("year", "2026").param("sort", "prj_nm,asc;"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sort"));
    }
    @Test void exportAcceptsSameEarlyYearRangeAsList() throws Exception {
        when(mapper.exportRows(any())).thenReturn(new ArrayList<>());
        mvc.perform(get("/api/assignments/export").param("year", "1899"))
            .andExpect(status().isOk());
        verify(mapper).exportRows(argThat(query -> query.year() == 1899));
    }
}
