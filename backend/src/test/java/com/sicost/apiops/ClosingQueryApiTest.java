package com.sicost.apiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.apiops.ClosingQueryModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ClosingQueryController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ClosingQueryService.class, PermissionService.class})
class ClosingQueryApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ClosingQueryMapper mapper;
    static final List<String> CODES = List.of("SALARY_UPLOAD", "MM_OVER", "DISPATCH_ATT", "BILL_UNCONFIRMED",
        "TAX_INVOICE_NO", "FIN_ENTRY", "PART_SETTLE", "PAYMENT", "BILL_MISSING");
    List<StoredCheck> stored(String month) {
        return CODES.stream().map(code -> new StoredCheck(code, code.equals("MM_OVER") ? "WARN" :
            code.equals("SALARY_UPLOAD") ? "FAIL" : "OK", code.equals("MM_OVER") ? 2 : 0,
            "저장된 판정 " + month, LocalDateTime.parse(month + "-28T10:30:00"))).toList();
    }
    @Test void approvedContractIsLoadedFromClasspath() throws Exception {
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("operationId: getClosing", "/api/closing:");
        }
    }
    @Test void anonymousMonthChangeReadsNineStoredResultsAndTheirOriginalTimes() throws Exception {
        for (String month : List.of("2026-10", "2026-11")) {
            when(mapper.checks(month)).thenReturn(stored(month));
            mvc.perform(get("/api/closing").param("month", month)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.month").value(month))
                .andExpect(jsonPath("$.data.status").value("OPEN")).andExpect(jsonPath("$.data.checks.length()").value(9))
                .andExpect(jsonPath("$.data.checks[1].status").value("WARN"))
                .andExpect(jsonPath("$.data.checks[1].count").value(2))
                .andExpect(jsonPath("$.data.checkedAt").value(month + "-28T10:30:00+09:00"))
                .andExpect(jsonPath("$.data.warningCount").value(1)).andExpect(jsonPath("$.data.failureCount").value(1));
            verify(mapper).state(month); verify(mapper).checks(month);
        }
        verifyNoMoreInteractions(mapper);
    }
    @Test void absentMonthRemainsOpenWithoutFabricatedChecksOrTimestamp() throws Exception {
        mvc.perform(get("/api/closing").param("month", "2027-01")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("OPEN")).andExpect(jsonPath("$.data.checks").isEmpty())
            .andExpect(jsonPath("$.data.checkedAt").doesNotExist()).andExpect(jsonPath("$.data.canReopen").value(false));
        verify(mapper).state("2027-01"); verify(mapper).checks("2027-01"); verifyNoMoreInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings = {"", "2026-13", "2026-1", "0000-01", " 2026-10", "2026-10-01"})
    void invalidMonthNeverReachesPersistence(String month) throws Exception {
        mvc.perform(get("/api/closing").param("month", month)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_MONTH")).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(mapper);
    }
    @Test void missingMonthIsInvalidMonth() throws Exception {
        mvc.perform(get("/api/closing")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MONTH"));
        verifyNoInteractions(mapper);
    }
    void closed() {
        when(mapper.state("2026-10")).thenReturn(new State("CLOSED",LocalDateTime.parse("2026-11-01T09:00:00"),null,null));
    }
    @Test void closedMissingSnapshotReturnsBusinessConflictWithoutRecalculation() throws Exception {
        closed();
        mvc.perform(get("/api/closing").param("month","2026-10")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MISSING_CLOSED_SNAPSHOT"));
        verify(mapper,never()).checks(anyString());
    }
    @ParameterizedTest @ValueSource(strings = {"null", "{}", "{\"snapshots\":{}}", "not-json"})
    void malformedOrIncompleteManifestCannotCertifyClosedMonth(String manifest) throws Exception {
        closed(); when(mapper.closeManifest("2026-10")).thenReturn(manifest);
        when(mapper.snapshotEvidence("2026-10")).thenReturn("{}");
        mvc.perform(get("/api/closing").param("month","2026-10")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MISSING_CLOSED_SNAPSHOT"));
    }
    @Test void databaseFailureDoesNotLeakPrivateDetails() throws Exception {
        when(mapper.state("2026-10")).thenThrow(new IllegalStateException("private SQL"));
        mvc.perform(get("/api/closing").param("month","2026-10")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }
}
