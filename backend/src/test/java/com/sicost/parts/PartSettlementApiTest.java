package com.sicost.parts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.costallocation.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.parts.PartSettlementModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PartSettlementController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, PartSettlementService.class,
    PartSettlementExportService.class, ExcelExportService.class, CurrentUser.class, PermissionService.class})
class PartSettlementApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartSettlementMapper mapper;
    @MockBean CostAllocationService allocations;
    @MockBean PartSettlementApprovalPolicy approval;
    @MockBean Clock clock;
    final Scope scope = new Scope(7, "2026-10");
    Settlement stored;
    final List<Audit> audits = new ArrayList<>();
    Settlement settlement(String status, long card, String note) {
        return new Settlement(91L, 7, "2026-10", status, 31L, "파트장", new BigDecimal("40"),
            10L, 1, 100, 60, 40, 16, card, 16-card, note, null);
    }
    @BeforeEach void setup() {
        stored = settlement("DRAFT", 0, null); audits.clear();
        Clock fixed = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(fixed.instant()); when(clock.getZone()).thenReturn(fixed.getZone());
        when(clock.withZone(any())).thenReturn(fixed);
        when(mapper.parts()).thenReturn(List.of(new Part(7, "영업 파트")));
        when(mapper.part(7)).thenReturn(new Part(7, "영업 파트"));
        when(mapper.lockPart(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 7L ? 7L : null);
        when(mapper.lockSettlement(scope)).thenReturn(91L);
        when(mapper.settlement(any())).thenAnswer(i -> scope.equals(i.getArgument(0)) ? stored : null);
        when(mapper.standards(7)).thenReturn(List.of(new Standard(31,"2026-01",null,"파트장",new BigDecimal("40"),10)));
        when(mapper.people(scope)).thenReturn(List.of(new Person(4,"개발자","FL",50,0,LocalDate.of(2025,1,1))));
        when(allocations.calculate("2026-10",null)).thenReturn(new CostAllocationModels.Allocation(
            "2026-10","2026-10",false,"원",List.of(),List.of(new CostAllocationModels.AllocationRow(
                "2026-10",4L,8L,3L,"PROJECT",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,50,0,100,100L,"N","N","N")),0));
        when(mapper.saveCard(any())).thenAnswer(i -> {
            CardWrite write = i.getArgument(0);
            stored = settlement("DRAFT", write.card().corp_card_amt(), write.card().rmrk_desc()); return 91L;
        });
        when(mapper.snapshot(any())).thenReturn(1);
        when(mapper.transition(any())).thenAnswer(i -> {
            Transition write = i.getArgument(0);
            stored = settlement(write.after(),stored.corp_card_amt(),stored.rmrk_desc()); return 1;
        });
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    @Test void publicListAndDetailUseEnvelopeAndPendingPolicyWithoutWrites() throws Exception {
        mvc.perform(get("/api/settlements/parts").param("month","2026-10").accept("application/json"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items[0].bsn_part_id").value(7));
        mvc.perform(get("/api/settlements/parts/7").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.provisional").value(true))
            .andExpect(jsonPath("$.data.confirmation_available").value(false))
            .andExpect(jsonPath("$.data.settlement.part_prft_amt").value(16));
        verify(mapper,never()).saveCard(any()); verify(mapper,never()).audit(any());
    }
    @Test void pendingConfirmationNeverChangesAnySnapshot() throws Exception {
        mvc.perform(post("/api/settlements/parts/7/confirm").param("month","2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_STANDARD_MISSING"));
        verify(mapper,never()).snapshot(any()); verify(mapper,never()).transition(any());
    }
    @Test void draftCardNaturalKeyPersistsBothChangedFieldsWithNullActor() throws Exception {
        mvc.perform(put("/api/settlements/parts/7/card").param("month","2026-10")
            .contentType("application/json").content("{\"corp_card_amt\":3,\"rmrk_desc\":\"검토\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlement.part_prft_amt").value(13))
            .andExpect(jsonPath("$.data.settlement.rmrk_desc").value("검토"));
        assertThat(audits).hasSize(1); assertThat(audits.get(0).actor()).isNull();
        assertThat(json.readTree(audits.get(0).before()).get("corp_card_amt").asLong()).isZero();
        assertThat(json.readTree(audits.get(0).after()).get("rmrk_desc").asText()).isEqualTo("검토");
        var order = inOrder(mapper); order.verify(mapper).lockPart(7); order.verify(mapper).lockSettlement(scope);
    }
    @ParameterizedTest @ValueSource(strings = {"{}","{\"corp_card_amt\":-1}","{\"corp_card_amt\":1.2}",
        "{\"corp_card_amt\":9223372036854775808}","{\"corp_card_amt\":0,\"approved\":true}","[]","null","{"})
    void cardValidationRejectsBeforeWrite(String body) throws Exception {
        mvc.perform(put("/api/settlements/parts/7/card").param("month","2026-10").contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper,never()).saveCard(any());
    }
    @Test void confirmedCardEvenZeroIsConflictAndOriginalUnchanged() throws Exception {
        stored = settlement("CONFIRM",9,"보존"); var before = stored;
        mvc.perform(put("/api/settlements/parts/7/card").param("month","2026-10")
            .contentType("application/json").content("{\"corp_card_amt\":0}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_ALREADY_CONFIRMED"));
        assertThat(stored).isEqualTo(before); verify(mapper,never()).saveCard(any());
    }
    @Test void historicalConfirmedReadDoesNotTouchCurrentSourcesOrApproval() throws Exception {
        stored = settlement("CONFIRM",4,"기록");
        mvc.perform(get("/api/settlements/parts/7").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlement.tot_sls_amt").value(100))
            .andExpect(jsonPath("$.data.provisional").value(false));
        verifyNoInteractions(allocations); verify(mapper,never()).people(any());
    }
    @Test void cancellationRestoresDraftPreservesDetailsAndAppendsReason() throws Exception {
        stored = settlement("CONFIRM",4,"기록");
        mvc.perform(post("/api/settlements/parts/7/cancel").param("month","2026-10")
            .contentType("application/json").content("{\"reason\":\"  재검토  \"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlement.stlm_stts_cd").value("DRAFT"))
            .andExpect(jsonPath("$.data.settlement.rmrk_desc").value("기록"));
        verify(mapper).historyWrite(argThat(t -> "DRAFT".equals(t.after()) && "재검토".equals(t.reason())));
        verify(mapper,never()).deleteDetails(anyLong()); verify(mapper,never()).insertDetail(any());
    }
    @ParameterizedTest @ValueSource(strings = {"{}","{\"reason\":\" \"}","{\"reason\":4}"})
    void cancelReasonRequired(String body) throws Exception {
        mvc.perform(post("/api/settlements/parts/7/cancel").param("month","2026-10")
            .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verify(mapper,never()).transition(any());
    }
    @Test void draftCancellationIsStale() throws Exception {
        mvc.perform(post("/api/settlements/parts/7/cancel").param("month","2026-10")
            .contentType("application/json").content("{\"reason\":\"취소\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_SETTLEMENT"));
    }
    @ParameterizedTest @ValueSource(strings = {"card","confirm","cancel"})
    void closedMonthBlocksEveryWrite(String action) throws Exception {
        when(mapper.lockMonth("2026-10")).thenReturn("CLOSED");
        var request = action.equals("card") ? put("/api/settlements/parts/7/card") : post("/api/settlements/parts/7/"+action);
        mvc.perform(request.param("month","2026-10").contentType("application/json")
            .content(action.equals("card") ? "{\"corp_card_amt\":0}" : "{\"reason\":\"취소\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper,never()).transition(any()); verify(mapper,never()).saveCard(any());
    }
    @Test void approvedPolicyAllowsOneConfirmationAndSecondIsStale() throws Exception {
        when(approval.approved()).thenReturn(true);
        mvc.perform(post("/api/settlements/parts/7/confirm").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlement.stlm_stts_cd").value("CONFIRM"));
        mvc.perform(post("/api/settlements/parts/7/confirm").param("month","2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_SETTLEMENT"));
        verify(mapper,times(1)).insertDetail(any()); verify(mapper,times(1)).historyWrite(any());
    }
    @Test void approvedButMissingStandardCannotConfirm() throws Exception {
        when(approval.approved()).thenReturn(true); when(mapper.standards(7)).thenReturn(List.of());
        mvc.perform(post("/api/settlements/parts/7/confirm").param("month","2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_STANDARD_MISSING"));
        verify(mapper,never()).snapshot(any());
    }
    @Test void lostConditionalTransitionIsStaleWithoutHistory() throws Exception {
        when(approval.approved()).thenReturn(true); doReturn(0).when(mapper).transition(any());
        mvc.perform(post("/api/settlements/parts/7/confirm").param("month","2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_SETTLEMENT"));
        verify(mapper,never()).historyWrite(any());
    }
    @ParameterizedTest @ValueSource(strings = {"0","-1","bad","9223372036854775808"})
    void invalidIdentifierIsValidationError(String id) throws Exception {
        mvc.perform(get("/api/settlements/parts/"+id).param("month","2026-10"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void absentMonthIsValidationError() throws Exception {
        mvc.perform(get("/api/settlements/parts").accept("application/json"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @ParameterizedTest @ValueSource(strings = {"2026-11","2026-13","2026-1","0000-01"})
    void futureOrMalformedMonthIsRejected(String month) throws Exception {
        mvc.perform(get("/api/settlements/parts/7").param("month",month)).andExpect(status().isBadRequest());
    }
    @Test void unknownViewIsRejected() throws Exception {
        mvc.perform(get("/api/settlements/parts/7").param("month","2026-10").param("view","TOTAL"))
            .andExpect(status().isBadRequest());
    }
    @Test void trendHasFiveMetricRowsAndJanThroughSelectedMonth() throws Exception {
        mvc.perform(get("/api/settlements/parts/7").param("month","2026-10").param("view","TREND"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.trend.length()").value(5))
            .andExpect(jsonPath("$.data.trend[0].months.length()").value(10))
            .andExpect(jsonPath("$.data.trend[0].months[0].month").value("2026-01"));
        verify(mapper,never()).saveCard(any());
    }
    @Test void missingStandardStillAllowsDraftCardSave() throws Exception {
        when(mapper.standards(7)).thenReturn(List.of());
        mvc.perform(put("/api/settlements/parts/7/card").param("month","2026-10")
            .contentType("application/json").content("{\"corp_card_amt\":0,\"rmrk_desc\":\"보존\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmation_available").value(false))
            .andExpect(jsonPath("$.data.settlement.rmrk_desc").value("보존"));
    }
    @ParameterizedTest @ValueSource(strings = {"card","confirm","cancel"})
    void pastYearIsReadOnlyEvenWithoutClosedRow(String action) throws Exception {
        var request = action.equals("card") ? put("/api/settlements/parts/7/card") : post("/api/settlements/parts/7/"+action);
        mvc.perform(request.param("month","2025-10").contentType("application/json")
            .content(action.equals("card") ? "{\"corp_card_amt\":0}" : "{\"reason\":\"취소\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper,never()).transition(any()); verify(mapper,never()).saveCard(any());
    }
    @Test void closedMissingAllocationIsSpecificConflictNotLiveFallback() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        when(allocations.calculate("2026-10",null)).thenThrow(ApiException.notFound("마감 배분자료 없음"));
        mvc.perform(get("/api/settlements/parts/7").param("month","2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_STANDARD_MISSING"))
            .andExpect(jsonPath("$.message").value("마감 배분자료 없음"));
        verify(mapper,never()).saveCard(any());
    }
    @Test void unknownPartIs404ForReadsAndCommands() throws Exception {
        mvc.perform(get("/api/settlements/parts/999").param("month","2026-10")).andExpect(status().isNotFound());
        mvc.perform(post("/api/settlements/parts/999/confirm").param("month","2026-10")).andExpect(status().isNotFound());
    }
    @Test void downloadNegotiatesRealXlsxAndAllPartsWithoutNewQuery() throws Exception {
        byte[] bytes = mvc.perform(get("/api/settlements/parts").param("month","2026-10")
            .accept("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetAt(0).getRow(4).getCell(0).getStringCellValue()).isEqualTo("영업 파트");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).contains("2026-10","view=MONTH");
        }
        verify(mapper).access(argThat(a -> "DOWNLOAD".equals(a.type()) && a.actor() == null));
    }
}
