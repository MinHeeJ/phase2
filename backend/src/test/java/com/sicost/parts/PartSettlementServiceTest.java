package com.sicost.parts;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.sicost.apiops.ProjectCostService;
import com.sicost.common.api.ApiException;
import com.sicost.common.costs.CostAllocationModels.Allocation;
import com.sicost.common.costs.CostAllocationModels.Result;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.sicost.parts.PartSettlementModels.*;

class PartSettlementServiceTest {
    PartSettlementMapper mapper;
    ProjectCostService costs;
    PartSettlementApproval approval;
    PartSettlementService service;
    final Key key = new Key(3, "2026-10");
    @BeforeEach void setup() {
        mapper = mock(PartSettlementMapper.class); costs = mock(ProjectCostService.class);
        approval = mock(PartSettlementApproval.class);
        service = new PartSettlementService(mapper, costs, new CurrentUser(), new PermissionService(),
            Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")), approval);
        when(mapper.lockPart(3)).thenReturn(new Part(3, "파트"));
        when(mapper.standard(key)).thenReturn(PartSettlementApiTest.STANDARD);
        when(mapper.insertDraft(key)).thenReturn(5L);
        when(mapper.card(any())).thenReturn(1);
        when(mapper.confirm(any())).thenReturn(1);
        when(mapper.cancel(any())).thenReturn(1);
    }
    @Test void productionPolicyBlocksBeforeLocksAndSourceReads() {
        assertThatThrownBy(() -> service.confirmCalculated(key)).isInstanceOfSatisfying(ApiException.class,
            ex -> { assertThat(ex.status()).isEqualTo(409); assertThat(ex.code()).isEqualTo("VALIDATION_ERROR"); });
        verifyNoInteractions(mapper, costs);
    }
    @Test void cardUsesGeneratedHeaderKeyAndAuditsAmountAndRemark() {
        service.saveCard(key, 100, "입력 비고");
        var order = inOrder(mapper);
        order.verify(mapper).lockPart(3); order.verify(mapper).lockHeader(key); order.verify(mapper).lockMonth("2026-10");
        order.verify(mapper).insertDraft(key); order.verify(mapper).card(new CardWrite(5, 100, null));
        order.verify(mapper).audit(new Audit(5, "corp_card_amt", "0", "100", "입력 비고", null));
        order.verify(mapper).audit(new Audit(5, "card_remark", null, "입력 비고", "입력 비고", null));
    }
    @Test void unchangedCardAndRemarkDoesNotTouchTimestampOrAudit() {
        when(mapper.lockHeader(key)).thenReturn(PartSettlementApiTest.header("DRAFT"));
        service.saveCard(key, 10, null);
        verify(mapper, never()).card(any()); verify(mapper, never()).audit(any());
    }
    @Test void confirmedCardRemainsUnchanged() {
        when(mapper.lockHeader(key)).thenReturn(PartSettlementApiTest.header("CONFIRM"));
        assertThatThrownBy(() -> service.saveCard(key, 100, "불가")).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("PART_ALREADY_CONFIRMED"));
        verify(mapper, never()).card(any()); verify(mapper, never()).audit(any());
    }
    @Test void negativeCardIsRejectedBeforeStorage() {
        assertThatThrownBy(() -> service.saveCard(key, -1, null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
    @Test void remarkCapacityIs500NotInventedColumnSize() {
        assertThatThrownBy(() -> service.saveCard(key, 0, "가".repeat(501))).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
    @Test void closedMonthCannotMutate() {
        when(mapper.lockMonth("2026-10")).thenReturn("CLOSED");
        assertThatThrownBy(() -> service.saveCard(key, 1, null)).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("CLOSED_MONTH"));
        verify(mapper, never()).insertDraft(any());
    }
    @Test void priorYearCannotMutate() {
        when(mapper.lockPart(3)).thenReturn(new Part(3, "파트"));
        assertThatThrownBy(() -> service.saveCard(new Key(3, "2025-10"), 1, null)).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("CLOSED_MONTH"));
        verify(mapper, never()).insertDraft(any());
    }
    @Test void cancellationRequiresReason() {
        for (String reason : Arrays.asList(null, " ", "x".repeat(501))) {
            assertThatThrownBy(() -> service.cancelCalculated(key, reason)).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(mapper);
    }
    @Test void cancellationPreservesPriorDetailsAndEvents() {
        when(mapper.lockHeader(key)).thenReturn(PartSettlementApiTest.header("CONFIRM"));
        service.cancelCalculated(key, "검토");
        verify(mapper).cancel(new CancelWrite(5, "검토", null));
        verify(mapper).event(new Event(5, "CANCEL", "검토", null));
        verify(mapper, never()).deleteDetails(anyLong());
        verify(mapper).access(new Event(5, "SETTLE_CANCEL", "검토", null));
    }
    @Test void cancellationOfDraftIsStaleNotSuccess() {
        when(mapper.lockHeader(key)).thenReturn(PartSettlementApiTest.header("DRAFT"));
        assertThatThrownBy(() -> service.cancelCalculated(key, "사유")).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("STALE_SETTLEMENT"));
        verify(mapper, never()).cancel(any());
    }
    @Test void secondApprovedTestConfirmationIsNotIdempotent() {
        when(approval.approved()).thenReturn(true);
        when(mapper.lockHeader(key)).thenReturn(PartSettlementApiTest.header("CONFIRM"));
        assertThatThrownBy(() -> service.confirmCalculated(key)).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("STALE_SETTLEMENT"));
        verify(mapper, never()).confirm(any());
    }
    @Test void missingStandardPreventsApprovedTestConfirmation() {
        when(approval.approved()).thenReturn(true);
        when(mapper.standard(key)).thenReturn(null);
        when(costs.allocation("2026-10", null)).thenReturn(new Result("2026-10", false, List.of(), List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> service.confirmCalculated(key)).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("PART_STANDARD_MISSING"));
        verify(mapper, never()).insertDraft(any());
    }
    @Test void approvedTestConfirmationSnapshotsOneDetailAndHeaderSameMonth() {
        when(approval.approved()).thenReturn(true);
        when(mapper.salaries(key)).thenReturn(List.of(new Salary(8, "인력", "FL", 100, 100, 0, LocalDate.of(2026, 10, 1))));
        when(costs.allocation("2026-10", null)).thenReturn(new Result("2026-10", false,
            List.of(new Allocation(8L, 9L, 10L, "PROJECT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                100, 0, 200L, 200L, false, false, false)), List.of(), List.of(), List.of()));
        service.confirmCalculated(key);
        var order = inOrder(mapper);
        order.verify(mapper).insertDraft(key); order.verify(mapper).deleteDetails(5);
        order.verify(mapper).insertDetail(argThat(w -> w.stlmId() == 5 && w.person().prft_amt() == 40));
        order.verify(mapper).confirm(argThat(s -> s.key().equals(key) && s.profit() == 40 && s.standard().part_std_hist_id() == 4));
        order.verify(mapper).event(new Event(5, "CONFIRM", null, null));
    }
}
