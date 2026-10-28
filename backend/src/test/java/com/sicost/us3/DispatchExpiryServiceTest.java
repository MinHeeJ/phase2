package com.sicost.us3;

import static com.sicost.changes.AssignmentChangeModels.*;
import static com.sicost.us3.DispatchExpiryRulesTest.param;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.apiops.ProjectBoardMapper;
import com.sicost.changes.AssignmentChangeMapper;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.*;

class DispatchExpiryServiceTest {
    final AssignmentChangeMapper mapper = mock(AssignmentChangeMapper.class);
    final ProjectBoardMapper standards = mock(ProjectBoardMapper.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneOffset.UTC);
    final DispatchExpiryService service = new DispatchExpiryService(mapper, standards, clock);

    @BeforeEach void setup() {
        when(standards.params("2026-10")).thenReturn(List.of(param("DISPATCH_MAX_MONTHS", "24", null),
            param("DISPATCH_ALERT_DAYS", null, "60,30")));
    }

    static State state(long id, String contract, String end) {
        return new State(id, 2, 3, LocalDate.parse("2025-09-01"), LocalDate.parse(end), BigDecimal.ONE,
            "NEW", null, null, "M", "ACTIVE", contract, "Y", "RUN", "FT", null);
    }

    @Test void usesServerSeoulTodayAndReadsFirstStartOnceForRepeatedIdentity() {
        when(mapper.state(1)).thenReturn(state(1, "DP", "2026-11-19"));
        when(mapper.firstStart(1)).thenReturn(LocalDate.parse("2024-11-20"));
        var snapshot = service.snapshot(List.of(1L, 1L));
        assertThat(snapshot.today()).isEqualTo("2026-10-14");
        assertThat(snapshot.assignments().get(1L).dispatch_display()).isEqualTo("파견 만료 D-36");
        verify(mapper).firstStart(1);
    }

    @Test void readsCurrentContractOnEveryRequestInsteadOfCachingPriorDp() {
        when(mapper.state(1)).thenReturn(state(1, "DP", "2026-11-19"), state(1, "MO", "2026-11-19"));
        when(mapper.firstStart(1)).thenReturn(LocalDate.parse("2024-11-20"));
        assertThat(service.snapshot(List.of(1L)).assignments().get(1L).within60()).isTrue();
        assertThat(service.snapshot(List.of(1L)).assignments().get(1L).dispatch_display()).isEqualTo("–");
        verify(mapper, times(1)).firstStart(1);
    }

    @Test void serverFilterUsesWholeSetBeforeTotalsAndPaginationAndKeepsOrder() {
        var ids = LongStream.rangeClosed(1, 105).boxed().toList();
        for (long id : ids) {
            when(mapper.state(id)).thenReturn(state(id, id > 50 ? "DP" : "MO", "2026-11-19"));
            if (id > 50) when(mapper.firstStart(id)).thenReturn(LocalDate.parse("2024-11-20"));
        }
        var snapshot = service.snapshot(ids);
        var filtered = service.filter(ids, Long::longValue, snapshot, true);
        assertThat(filtered).hasSize(55).startsWith(51L, 52L).endsWith(105L);
        assertThat(filtered.stream().limit(50).toList()).endsWith(100L);
        assertThat(service.filter(ids, Long::longValue, snapshot, false)).containsExactlyElementsOf(ids);
    }

    @Test void withdrawnOverrunIsRemovedBeforePagination() {
        when(mapper.state(1)).thenReturn(state(1, "DP", "2026-10-13"));
        when(mapper.firstStart(1)).thenReturn(LocalDate.parse("2023-01-01"));
        var snapshot = service.snapshot(List.of(1L));
        assertThat(service.filter(List.of(1L), Long::longValue, snapshot, true)).isEmpty();
    }

    @Test void unknownAssignmentUsesExisting404() {
        assertThatThrownBy(() -> service.snapshot(List.of(99L))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.status()).isEqualTo(404));
    }
}
