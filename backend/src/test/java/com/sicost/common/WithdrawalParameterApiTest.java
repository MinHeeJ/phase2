package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sicost.apiops.*;
import com.sicost.apiops.ProjectBoardModels.OperatingParam;
import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
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
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ProjectBoardController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ProjectBoardService.class,
    ProgressParameterService.class, ProjectCostService.class, PermissionService.class,
    WithdrawalParameterApiTest.Time.class})
class WithdrawalParameterApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-12-31T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired MockMvc mvc;
    @MockBean ProjectBoardMapper mapper;

    private OperatingParam number(String key, String value) {
        return new OperatingParam(key, "2000-01", key, new BigDecimal(value), null, null, null);
    }

    private List<OperatingParam> parameters() {
        return new ArrayList<>(List.of(
            number("SIG_DELAY_WARN", "5"), number("SIG_DELAY_RISK", "10"),
            number("SIG_COST_WARN", "5"), number("SIG_COST_RISK", "15"),
            number("SIG_STALE_DAYS", "14"), number("BUDGET_RATE", "80"),
            number("DISPATCH_MAX_MONTHS", "18"),
            new OperatingParam("DISPATCH_ALERT_DAYS", "2000-01", "파견 알림", null, "45,15", "일", null),
            number("PLAN_GAP_TOLERANCE", "0.25")
        ));
    }

    @BeforeEach void setup() {
        when(mapper.params(anyString())).thenReturn(parameters());
    }

    @Test void anonymousParamsIncludesAllNineWithoutParsingAlertTextAsOneNumber() throws Exception {
        var result = mvc.perform(get("/api/params"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.data.length()").value(9))
            .andExpect(jsonPath("$.data[6].num_val").value(18))
            .andExpect(jsonPath("$.data[7].txt_val").value("45,15"))
            .andExpect(jsonPath("$.data[7].num_val").isEmpty())
            .andExpect(jsonPath("$.data[8].num_val").value(0.25))
            .andReturn();
        assertThat(result.getResponse().getHeader("X-Request-Id")).isNotBlank();
        verify(mapper).params("2027-01");
    }

    @Test void requestedHistoricalMonthIsNotReplacedByToday() throws Exception {
        mvc.perform(get("/api/params").param("month", "2025-02"))
            .andExpect(status().isOk());
        verify(mapper).params("2025-02");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2026-1", "2026-00", "2026-13", "2026-10-14", "not-a-month"})
    void invalidMonthUsesExistingFlat400Envelope(String month) throws Exception {
        var result = mvc.perform(get("/api/params").param("month", month))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.error").doesNotExist())
            .andReturn();
        assertThat(result.getResponse().getContentAsString())
            .contains(result.getResponse().getHeader("X-Request-Id"));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIG_DELAY_WARN", "SIG_DELAY_RISK", "SIG_COST_WARN", "SIG_COST_RISK", "SIG_STALE_DAYS", "BUDGET_RATE"})
    void everyExistingMissingProgressKeyStillFails(String key) throws Exception {
        when(mapper.params(anyString())).thenReturn(parameters().stream()
            .filter(p -> !key.equals(p.oprt_std_id())).toList());
        mvc.perform(get("/api/params"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(key)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIG_DELAY_WARN", "SIG_DELAY_RISK", "SIG_COST_WARN", "SIG_COST_RISK", "SIG_STALE_DAYS", "BUDGET_RATE"})
    void everyExistingInvalidProgressNumberStillFails(String key) throws Exception {
        var rows = parameters();
        rows.replaceAll(p -> key.equals(p.oprt_std_id())
            ? new OperatingParam(key, "2000-01", key, null, "invalid", null, null) : p);
        when(mapper.params(anyString())).thenReturn(rows);
        mvc.perform(get("/api/params"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("month"));
    }

    @Test void oldTextualProgressNumbersRemainAccepted() throws Exception {
        var rows = parameters();
        rows.set(0, new OperatingParam("SIG_DELAY_WARN", "2000-01", "주의", null, "5.25", "%p", null));
        when(mapper.params(anyString())).thenReturn(rows);
        mvc.perform(get("/api/params"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].txt_val").value("5.25"));
    }
}
