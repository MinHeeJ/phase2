package com.sicost.us3;

import static com.sicost.changes.AssignmentChangeModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.changes.*;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AssignmentChangeController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, AssignmentChangeService.class,
    AssignmentConcurrencyService.class, CurrentUser.class, PermissionService.class, DispatchExpiryApiTest.Time.class})
class DispatchExpiryApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AssignmentChangeMapper mapper;
    @MockBean AssignmentConcurrencyMapper concurrency;
    final LocalDateTime version = LocalDateTime.parse("2026-10-13T12:00:00.123456");

    @BeforeEach void setup() {
        when(concurrency.lockProject(2)).thenReturn(2L);
        when(concurrency.lockAssignment(1)).thenReturn(
            new AssignmentConcurrencyMapper.AssignmentLock(1, version, version.plusDays(1)));
        when(concurrency.advance(any())).thenReturn(version.plusDays(1));
        when(mapper.dispatchMonths(anyString())).thenReturn(24);
        when(mapper.codeName(any())).thenReturn("고객 요청");
        when(mapper.insert(any())).thenReturn(1L);
        when(mapper.row(any())).thenReturn(new ChangeRow(1, 1, "EXTEND", LocalDate.parse("2026-10-14"),
            "이전", "이후", null, null, null, null, "N", "N", null, null, null, null, null, version));
    }

    void state(String first, String oldEnd, String contract) {
        when(mapper.state(1)).thenReturn(new State(1, 2, 3, LocalDate.parse(first), LocalDate.parse(oldEnd),
            BigDecimal.ONE, "NEW", null, null, "M", "ACTIVE", contract, "Y", "RUN", "FT", null));
        when(mapper.firstStart(1)).thenReturn(LocalDate.parse(first));
    }

    void extend(String oldEnd, String newEnd, boolean warned) throws Exception {
        var body = Map.of("updated_at", version.toString(), "asgn_chg_ty_cd", "EXTEND",
            "aply_dt", LocalDate.parse(oldEnd).plusDays(1).toString(), "end_dt", newEnd, "rsn_cd", "CUST");
        var result = mvc.perform(post("/api/assignments/1/changes").contentType("application/json")
            .content(json.writeValueAsBytes(body))).andExpect(status().isOk());
        if (warned) result.andExpect(jsonPath("$.data.assignment.warnings[?(@.code == 'DISPATCH_MAX_MONTHS')]").isNotEmpty());
        else result.andExpect(jsonPath("$.data.assignment.warnings[?(@.code == 'DISPATCH_MAX_MONTHS')]").isEmpty());
    }

    @Test void normalExtensionWarnsOnlyBeyondStrictExpiry() throws Exception {
        state("2024-11-20", "2026-10-31", "DP");
        extend("2026-10-31", "2026-11-20", true);
        verify(mapper).dispatchMonths("2026-11");
    }

    @Test void exactExpiryIsNotOverrun() throws Exception {
        state("2024-11-20", "2026-10-31", "DP");
        extend("2026-10-31", "2026-11-19", false);
    }

    @Test void leapDayOneDayBeyondLiteralExpiryWarns() throws Exception {
        state("2024-02-29", "2026-02-26", "DP");
        extend("2026-02-26", "2026-02-28", true);
        verify(mapper).dispatchMonths("2026-02");
    }

    @ParameterizedTest @ValueSource(strings = {"MO", "TK", "IN"})
    void otherCurrentContractsHaveNoDispatchWarning(String contract) throws Exception {
        state("2024-02-29", "2026-02-26", contract);
        extend("2026-02-26", "2026-02-28", false);
        verify(mapper, never()).dispatchMonths(anyString());
    }

    @Test void periodCorrectionUsesSameExpiryFunction() throws Exception {
        state("2024-02-29", "2026-02-26", "DP");
        mvc.perform(post("/api/assignments/1/changes").contentType("application/json")
            .content(json.writeValueAsBytes(Map.of("updated_at", version.toString(), "asgn_chg_ty_cd", "PERIOD",
                "aply_dt", "2026-10-14", "end_dt", "2026-02-28", "asgn_se_cd", "ADD", "rsn_cd", "CUST"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignment.warnings[?(@.code == 'DISPATCH_MAX_MONTHS')]").isNotEmpty());
    }
}
