package com.sicost.parts;

import com.sicost.common.api.*;
import com.sicost.common.costallocation.*;
import com.sicost.common.costallocation.CostAllocationModels.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.parts.PartSettlementModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PartSettlementController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, PartSettlementService.class,
    PartSettlementExportService.class, ExcelExportService.class, CurrentUser.class, PartSettlementApiTest.Time.class})
class PartSettlementApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean PartSettlementMapper mapper;
    @MockBean CostAllocationService allocation;
    @MockBean PartSettlementApprovalPolicy approval;
    @MockBean PermissionService permissions;
    AtomicReference<Header> saved;
    static final Key KEY = new Key(7, "2026-10");
    static final Standard STANDARD = new Standard(9, "2026-01", null, "파트장", new BigDecimal("40"), 0);
    static Header header(String state, long card) {
        return new Header(31L, 7, "2026-10", 9L, new BigDecimal("40"), 0L, 2,
            280L, 200L, 80L, 32L, card, 32L-card, state,
            "CONFIRM".equals(state) ? LocalDateTime.parse("2026-10-29T09:00:00") : null);
    }
    @BeforeEach void setup() {
        saved = new AtomicReference<>();
        when(mapper.parts()).thenReturn(List.of(new Part(7,"영업파트")));
        when(mapper.part(7)).thenReturn(new Part(7,"영업파트"));
        when(mapper.lockPart(7)).thenReturn(7L);
        when(mapper.standard(any())).thenReturn(STANDARD);
        when(mapper.standards(7)).thenReturn(List.of(STANDARD));
        when(mapper.header(KEY)).thenAnswer(i -> saved.get());
        when(mapper.lockHeader(KEY)).thenAnswer(i -> saved.get());
        doAnswer(i -> { saved.compareAndSet(null, header("DRAFT", 0)); return null; }).when(mapper).ensureDraft(KEY);
        when(mapper.salaries(KEY)).thenReturn(List.of(
            new Salary(11,"첫사람","FT","DIRECT",100,0,LocalDate.parse("2025-01-01"),"프로젝트"),
            new Salary(12,"둘째사람","FL","DIRECT",100,0,LocalDate.parse("2025-01-01"),"프로젝트")));
        when(allocation.get("2026-10", null)).thenReturn(new Result("2026-10","OPEN","2026-10","KRW",
            List.of(row(11,200), row(12,80)),List.of(),List.of(),List.of()));
        when(mapper.card(any())).thenAnswer(i -> {
            CardWrite w=i.getArgument(0); saved.set(header("DRAFT",w.amount())); return 1;
        });
        when(mapper.confirm(any())).thenAnswer(i -> {
            saved.set(header("CONFIRM",saved.get().corp_card_amt())); return 1;
        });
        when(mapper.cancel(any())).thenAnswer(i -> {
            saved.set(header("DRAFT",saved.get().corp_card_amt())); return 1;
        });
    }
    static Allocation row(long id,long revenue) {
        return new Allocation("2026-10",id,1L,id,"PROJECT",new BigDecimal("1.00"),
            new BigDecimal("1.00"),BigDecimal.ONE,100L,0,revenue,200L,"N","N","N");
    }
    @Test void anonymousListIsProvisionalAndReadOnlyWithFullSummary() throws Exception {
        mvc.perform(get("/api/settlements/parts").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(50))
            .andExpect(jsonPath("$.data.items[0].part_prft_amt").value(32))
            .andExpect(jsonPath("$.data.items[0].provisional").value(true))
            .andExpect(jsonPath("$.data.summary.part_count").value(1));
        verify(permissions).can("UI-19.read");
        verify(mapper,never()).ensureDraft(any()); verify(mapper,never()).audit(any());
    }
    @Test void detailUsesMonthlySalaryAndIncludesWarningAndMetadata() throws Exception {
        mvc.perform(get("/api/settlements/parts/7").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.unit").value("KRW"))
            .andExpect(jsonPath("$.data.items[1].prft_amt").value(-8))
            .andExpect(jsonPath("$.data.confirmation_allowed").value(false));
    }
    @Test void cardCreatesByNaturalKeyAndAuditsNoteWithNullActor() throws Exception {
        mvc.perform(put("/api/settlements/parts/7/card").param("month","2026-10")
            .contentType("application/json").content("{\"corp_card_amt\":100,\"note\":\"식대\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlement.part_prft_amt").value(-68));
        verify(mapper).ensureDraft(KEY);
        verify(mapper).audit(new Audit("31","corp_card_amt","0","100","식대",null));
        verify(permissions).can("UI-19.update");
    }
    @Test void noteOnlyUpdateAndBlankClearAppendAudit() throws Exception {
        saved.set(header("DRAFT",100)); when(mapper.note(31)).thenReturn("기존");
        mvc.perform(put("/api/settlements/parts/7/card").param("month","2026-10")
            .contentType("application/json").content("{\"corp_card_amt\":100,\"note\":\"  \"}"))
            .andExpect(status().isOk());
        verify(mapper).audit(new Audit("31","corp_card_amt","100","100","",null));
        verify(mapper).audit(new Audit("31","note","기존","","카드 비고 변경",null));
    }
    @Test void omittedNoteRetainsLatestNote() throws Exception {
        saved.set(header("DRAFT",10)); when(mapper.note(31)).thenReturn("유지");
        mvc.perform(put("/api/settlements/parts/7/card").param("month","2026-10")
            .contentType("application/json").content("{\"corp_card_amt\":11}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.note").value("유지"));
        verify(mapper).audit(new Audit("31","corp_card_amt","10","11","유지",null));
    }
    @Test void productionConfirmationIsApprovalPendingWithoutInsertingHeader() throws Exception {
        mvc.perform(post("/api/settlements/parts/7/confirm").param("month","2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_STANDARD_MISSING"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("운영 승인 대기")));
        verify(mapper,never()).ensureDraft(any()); verify(mapper,never()).confirm(any());
    }
    @Test void approvedInternalPolicySnapshotsAndSecondConfirmationIsStale() throws Exception {
        when(approval.approved(any(),any())).thenReturn(true);
        mvc.perform(post("/api/settlements/parts/7/confirm").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlement.stlm_stts_cd").value("CONFIRM"));
        verify(mapper,times(2)).insertDetail(any());
        verify(mapper).insertHistory(new HistoryWrite(31,"CONFIRM",null,null));
        mvc.perform(post("/api/settlements/parts/7/confirm").param("month","2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_SETTLEMENT"));
        verify(mapper,times(1)).confirm(any());
    }
    @Test void missingApplicableHistoryCannotBeApproved() throws Exception {
        when(mapper.standard(any())).thenReturn(null); when(approval.approved(any(),any())).thenReturn(true);
        mvc.perform(post("/api/settlements/parts/7/confirm").param("month","2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_STANDARD_MISSING"));
    }
    @Test void confirmedCardIsImmutable() throws Exception {
        Header original=header("CONFIRM",10); saved.set(original);
        mvc.perform(put("/api/settlements/parts/7/card").param("month","2026-10")
            .contentType("application/json").content("{\"corp_card_amt\":99}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_ALREADY_CONFIRMED"));
        assertThat(saved.get()).isEqualTo(original); verify(mapper,never()).card(any());
    }
    @Test void cancellationRequiresConfirmAndPreservesSnapshotsAndHistory() throws Exception {
        saved.set(header("CONFIRM",10));
        mvc.perform(post("/api/settlements/parts/7/cancel").param("month","2026-10")
            .contentType("application/json").content("{\"reason\":\" 원천 정정 \"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlement.stlm_stts_cd").value("DRAFT"));
        verify(mapper).insertHistory(new HistoryWrite(31,"CANCEL","원천 정정",null));
        verify(mapper,never()).clearDetails(anyLong()); verify(mapper,never()).insertDetail(any());
    }
    @Test void draftCancellationIsStale() throws Exception {
        saved.set(header("DRAFT",10));
        mvc.perform(post("/api/settlements/parts/7/cancel").param("month","2026-10")
            .contentType("application/json").content("{\"reason\":\"정정\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_SETTLEMENT"));
        verify(mapper,never()).cancel(any());
    }
    @ParameterizedTest @ValueSource(strings={"card","confirm","cancel"})
    void closedMonthRejectsAllMutations(String action) throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED"); saved.set(header("CONFIRM",10));
        var request="card".equals(action)?put("/api/settlements/parts/7/card"):post("/api/settlements/parts/7/"+action);
        mvc.perform(request.param("month","2026-10").contentType("application/json")
            .content("card".equals(action)?"{\"corp_card_amt\":99}":"cancel".equals(action)?"{\"reason\":\"정정\"}":"{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper,never()).audit(any());
    }
    @Test void pastYearIsReadOnly() throws Exception {
        mvc.perform(put("/api/settlements/parts/7/card").param("month","2025-10")
            .contentType("application/json").content("{\"corp_card_amt\":1}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
    }
    @ParameterizedTest @ValueSource(strings={"{\"corp_card_amt\":-1}","{\"corp_card_amt\":1.5}","{}","{\"corp_card_amt\":\"1\"}","{", "{\"corp_card_amt\":1} {}"})
    void invalidCardIsFeatureValidation(String body) throws Exception {
        mvc.perform(put("/api/settlements/parts/7/card").param("month","2026-10")
            .contentType("application/json").content(body)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(mapper,never()).ensureDraft(any());
    }
    @ParameterizedTest @ValueSource(strings={"{\"reason\":\"  \"}","{}","{\"reason\":1}"})
    void blankOrMissingReasonIsValidation(String body) throws Exception {
        mvc.perform(post("/api/settlements/parts/7/cancel").param("month","2026-10")
            .contentType("application/json").content(body)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("reason"));
    }
    @Test void clientCannotApproveOrSupplyTotals() throws Exception {
        mvc.perform(post("/api/settlements/parts/7/confirm").param("month","2026-10")
            .contentType("application/json").content("{\"approved\":true}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("approved"));
        verifyNoInteractions(approval);
    }
    @Test void notFoundAndMalformedIdentityAndViewAndMonth() throws Exception {
        mvc.perform(get("/api/settlements/parts/999").param("month","2026-10"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/settlements/parts/01").param("month","2026-10"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("partId"));
        mvc.perform(get("/api/settlements/parts/7").param("month","2026-10").param("view","OTHER"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("view"));
        mvc.perform(get("/api/settlements/parts"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
    }
    @Test void confirmedReadNeverRecalculatesAndTrendUsesFiveRows() throws Exception {
        saved.set(header("CONFIRM",10));
        when(mapper.details(31)).thenReturn(List.of(new PartSettlementModels.Person(11,"저장인력","FT",null,
            new BigDecimal("1.00"),200L,100L,0L,0L,100L,100L,40L,"영업비+퇴직금")));
        mvc.perform(get("/api/settlements/parts/7").param("month","2026-10").param("view","TREND"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.months.length()").value(10))
            .andExpect(jsonPath("$.data.months[9].stlm_stts_cd").value("CONFIRM"))
            .andExpect(jsonPath("$.data.trend.length()").value(5))
            .andExpect(jsonPath("$.data.trend[4].values[9]").value(40));
        verifyNoInteractions(allocation); verify(mapper,never()).salaries(KEY);
    }
    @Test void emptyListStillHasMetadataAndSummary() throws Exception {
        when(mapper.parts()).thenReturn(List.of());
        mvc.perform(get("/api/settlements/parts").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.summary.part_count").value(0));
    }
    @Test void workbookValidationFailureKeepsJsonErrorEnvelope() throws Exception {
        mvc.perform(get("/api/settlements/parts").param("month", "invalid")
            .accept("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(mapper, never()).access(any());
    }
    @Test void negotiatedDownloadIsRealXlsxAndAuditedOnce() throws Exception {
        var result=mvc.perform(get("/api/settlements/parts").param("month","2026-10").param("page","10")
            .accept("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(4).getCell(0).getStringCellValue()).isEqualTo("영업파트");
        }
        verify(mapper,times(1)).access(any()); verify(permissions).can("UI-19.download");
    }
}
