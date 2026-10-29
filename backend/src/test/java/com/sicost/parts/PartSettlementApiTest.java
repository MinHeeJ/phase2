package com.sicost.parts;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.apiops.ProjectCostService;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.costs.CostAllocationModels.Result;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.parts.PartSettlementModels.*;

@WebMvcTest(controllers = PartSettlementController.class)
@Import({PartSettlementService.class, PartSettlementApproval.class, CurrentUser.class,
    PermissionService.class, ApiExceptionHandler.class, RequestIdFilter.class, PartSettlementApiTest.Time.class})
class PartSettlementApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean PartSettlementMapper mapper;
    @MockBean ProjectCostService costs;
    static final Key KEY = new Key(3, "2026-10");
    static final Standard STANDARD = new Standard(4, "파트장", new BigDecimal("40"), 0);
    @BeforeEach void setup() {
        when(mapper.parts()).thenReturn(List.of(new Part(3, "영업 파트")));
        when(mapper.part(3)).thenReturn(new Part(3, "영업 파트"));
        when(mapper.standard(any())).thenReturn(STANDARD);
        when(costs.allocation(anyString(), isNull())).thenAnswer(i -> new Result(i.getArgument(0), false,
            List.of(), List.of(), List.of(), List.of()));
    }
    @Test void listUsesPublicEnvelopeAndProvisionalNoWrites() throws Exception {
        mvc.perform(get("/api/settlements/parts").param("month", "2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data[0].part.bsn_part_id").value(3))
            .andExpect(jsonPath("$.data[0].settlement.provisional").value(true));
        verify(mapper, never()).insertDraft(any());
    }
    @Test void monthReadsOnlySelectedMonth() throws Exception {
        mvc.perform(get("/api/settlements/parts/3").param("month", "2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.months.length()").value(1))
            .andExpect(jsonPath("$.data.view").value("MONTH"));
        verify(mapper).salaries(KEY);
    }
    @Test void trendStartsJanuaryAndEndsSelectedMonth() throws Exception {
        mvc.perform(get("/api/settlements/parts/3").param("month", "2026-03").param("view", "TREND"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.months.length()").value(3))
            .andExpect(jsonPath("$.data.months[0].month").value("2026-01"))
            .andExpect(jsonPath("$.data.months[2].month").value("2026-03"));
    }
    @ParameterizedTest @ValueSource(strings = {"2026-13", "0000-01", "2026-1", " ", "2026-10-01"})
    void invalidMonthUsesNewErrorCode(String month) throws Exception {
        mvc.perform(get("/api/settlements/parts").param("month", month)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(mapper, costs);
    }
    @Test void missingMonthIsValidationError() throws Exception {
        mvc.perform(get("/api/settlements/parts")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidStringStorageKey(String id) throws Exception {
        mvc.perform(get("/api/settlements/parts/" + id).param("month", "2026-10"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("partId"));
    }
    @Test void missingOrHeadquartersIsNotFound() throws Exception {
        mvc.perform(get("/api/settlements/parts/8").param("month", "2026-10")).andExpect(status().isNotFound());
    }
    @Test void invalidViewIsRejected() throws Exception {
        mvc.perform(get("/api/settlements/parts/3").param("month", "2026-10").param("view", "YEAR"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("view"));
    }
    @Test void productionConfirmationIsAlwaysPendingAndDoesNotAccessStorage() throws Exception {
        mvc.perform(post("/api/settlements/parts/3/confirm").param("month", "2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("운영 승인 대기")));
        verifyNoInteractions(mapper, costs);
        assertThat(new PartSettlementApproval().approved()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings = {"card", "cancel", "confirm"})
    void unsupportedBodyNeverWrites(String action) throws Exception {
        var request = "card".equals(action) ? put("/api/settlements/parts/3/" + action) : post("/api/settlements/parts/3/" + action);
        mvc.perform(request.param("month", "2026-10").contentType(MediaType.APPLICATION_JSON).content("{\"unknown\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(mapper, costs);
    }
    @ParameterizedTest @ValueSource(strings = {"card", "cancel"})
    void absentWriteSchemaIsExplicitAndNoMutation(String action) throws Exception {
        var request = "card".equals(action) ? put("/api/settlements/parts/3/" + action) : post("/api/settlements/parts/3/" + action);
        mvc.perform(request.param("month", "2026-10")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper, never()).card(any()); verify(mapper, never()).cancel(any());
    }
    @Test void confirmedCardIsLocked() throws Exception {
        when(mapper.header(KEY)).thenReturn(header("CONFIRM"));
        mvc.perform(put("/api/settlements/parts/3/card").param("month", "2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_ALREADY_CONFIRMED"));
        verify(mapper, never()).card(any());
    }
    @Test void closedCancelIsLocked() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        mvc.perform(post("/api/settlements/parts/3/cancel").param("month", "2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper, never()).cancel(any());
    }
    @Test void confirmedDetailUsesFrozenStandardsAndNeverRecalculates() throws Exception {
        when(mapper.header(KEY)).thenReturn(header("CONFIRM"));
        mvc.perform(get("/api/settlements/parts/3").param("month", "2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.months[0].provisional").value(false))
            .andExpect(jsonPath("$.data.months[0].part_prft_amt").value(22));
        verifyNoInteractions(costs); verify(mapper, never()).standard(any()); verify(mapper).details(5);
    }
    static Header header(String status) {
        return new Header(5, 3, "2026-10", 4L, new BigDecimal("40"), 0L, 2, 100L, 20L, 80L, 32L, 10, 22L, status, null);
    }
}
