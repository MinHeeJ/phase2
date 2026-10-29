package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.*;
import com.sicost.common.persistence.AssignmentConcurrencyService;
import com.sicost.common.security.CurrentUser;
import com.sicost.shared.SharedReadMapper;
import com.sicost.shared.SharedReadModels.MonthClose;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BillingMutationTest {
    final BillingMutationMapper mapper = mock(BillingMutationMapper.class);
    final AssignmentConcurrencyService locks = mock(AssignmentConcurrencyService.class);
    final SharedReadMapper shared = mock(SharedReadMapper.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-31T15:00:00Z"), ZoneOffset.UTC);
    final BillingMutationService service = new BillingMutationService(mapper, locks, shared, new CurrentUser(), clock);
    State state(long id, Long original) {
        return new State(id, 37, original, "DRAFT", "MO", "2026-10", null, null,
            LocalDateTime.of(2026, 11, 1, 0, 0));
    }
    @Test void locksProjectBeforeOriginalBeforeAmendment() {
        when(mapper.state(2)).thenReturn(state(2, 1L));
        when(mapper.lockBill(1)).thenReturn(state(1, null));
        when(mapper.lockBill(2)).thenReturn(state(2, 1L));
        assertThat(service.lock(2).bill_id()).isEqualTo(2);
        var order = inOrder(mapper, locks);
        order.verify(mapper).state(2); order.verify(locks).lockProject(37);
        order.verify(mapper).lockBill(1); order.verify(mapper).lockBill(2);
    }
    @Test void numberingUsesTransactionAdvisoryLockBeforeReadingMaximum() {
        when(mapper.maximumNumber("B-2611-")).thenReturn(99L);
        assertThat(service.nextNumber()).isEqualTo("B-2611-100");
        var order = inOrder(mapper);
        order.verify(mapper).numberLock("B-2611-"); order.verify(mapper).maximumNumber("B-2611-");
    }
    @Test void receiptEditChecksOldClosedMonthEvenWhenNewMonthIsOpen() {
        when(shared.monthClose("2026-09", "2026-09")).thenReturn(List.of(new MonthClose("2026-09", "CLOSED")));
        assertThatThrownBy(() -> service.requireReceiptMonths(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        verifyNoInteractions(mapper);
    }
    @Test void receiptEditChecksProposedMonthIndependently() {
        when(shared.monthClose("2026-09", "2026-09")).thenReturn(List.of(new MonthClose("2026-09", "CLOSED")));
        assertThatThrownBy(() -> service.requireReceiptMonths(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 30)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        verify(shared).monthClose("2026-10", "2026-10");
    }
    @Test void absentMonthRowsAreOpenNotImplicitlyClosedByYear() {
        service.requireOpen(YearMonth.of(2025, 10), "month");
        verify(shared).monthClose("2025-10", "2025-10");
    }
    @Test void boundedAuditOverridesCallerActorWithCurrentUser() {
        service.audit(new Audit("bill.rcpt", "73", "UPDATE", "rcpt_amt", "100", "200", "입금 정정", 99L));
        verify(mapper).audit(new Audit("bill.rcpt", "73", "UPDATE", "rcpt_amt", "100", "200", "입금 정정", null));
    }
    @ParameterizedTest @ValueSource(strings = {"bill.dun_hist", "prj.prj", "asgn.asgn", "fin.entry"})
    void auditScopeDoesNotPermitExcludedWrites(String table) {
        assertThatThrownBy(() -> service.audit(new Audit(table, "1", "UPDATE", null, null, "변경", null, null)))
            .isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
    @Test void oversizedAuditIsRejectedBeforeSqlRatherThanTruncated() {
        assertThatThrownBy(() -> service.audit(new Audit("bill.bill", "1", "UPDATE", null, null,
            "가".repeat(4001), null, null))).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
    @Test void oversizedReasonIsRejectedBeforeSql() {
        assertThatThrownBy(() -> service.audit(new Audit("bill.bill", "1", "UPDATE", null, null,
            "변경", "가".repeat(501), null))).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
}
