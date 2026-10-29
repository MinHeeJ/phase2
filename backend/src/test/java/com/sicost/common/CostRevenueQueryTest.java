package com.sicost.common;

import com.sicost.billingsops.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.PermissionService;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import static com.sicost.billingsops.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostRevenueQueryTest {
    BillingMapper mapper;
    BillingRevenueQuery query;
    @BeforeEach void setup() {
        mapper = mock(BillingMapper.class);
        query = new BillingRevenueQuery(mapper, null, Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC), new PermissionService());
    }
    @Test void projectMonthRevenueUsesExistingSingleRevenueServiceAndKeepsNegativeSupply() {
        var scope = new Scope(19, "2026-10", null);
        when(mapper.projectRevenue(scope)).thenReturn(List.of(new Revenue("2026-10", -100)));
        assertThat(query.revenue(19, "2026-10")).isEqualTo(-100);
        verify(mapper).projectRevenue(scope); verifyNoMoreInteractions(mapper);
    }
    @Test void noConfirmedRowsIsKnownAdditiveZero() {
        assertThat(query.revenue(19, "2026-10")).isZero();
    }
    @Test void companyYearRevenueStillUsesItsExistingQuery() {
        var expected = List.of(new Revenue("2026-10", 900));
        when(mapper.revenue(2026)).thenReturn(expected);
        assertThat(query.revenue(2026)).isEqualTo(expected);
        verify(mapper).revenue(2026); verifyNoMoreInteractions(mapper);
    }
    @Test void malformedMonthIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> query.revenue(19, "2026-13")).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.field()).isEqualTo("month"));
        verifyNoInteractions(mapper);
    }
    @Test void invalidProjectIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> query.revenue(0, "2026-10")).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.field()).isEqualTo("projectId"));
        verifyNoInteractions(mapper);
    }
}
