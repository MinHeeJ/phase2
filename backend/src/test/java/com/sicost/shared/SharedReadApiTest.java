package com.sicost.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sicost.common.api.ApiExceptionHandler;
import com.sicost.common.api.RequestIdFilter;
import com.sicost.common.health.HealthController;
import com.sicost.shared.SharedReadModels.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {SharedReadController.class, HealthController.class})
@Import({RequestIdFilter.class, ApiExceptionHandler.class, SharedReadService.class})
class SharedReadApiTest {
    @Autowired MockMvc mvc;
    @MockBean SharedReadMapper mapper;

    @BeforeEach void existingGroup() { when(mapper.groupExists("JOB")).thenReturn(true); }

    @Test void operationNamesMatchDurableContract() throws Exception {
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("operationId: getMenus", "operationId: getCodeOptions", "operationId: getMonthClose", "operationId: getEffectiveParam", "operationId: getHealth");
        }
    }
    @Test void activeOptionsHaveFlatArrayAndExplicitNullableAttributes() throws Exception {
        when(mapper.codeOptions("JOB")).thenReturn(List.of(new CodeOption("BE", "백엔드", "200000", null, null)));
        mvc.perform(get("/api/codes/JOB")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.data[0].code").value("BE")).andExpect(jsonPath("$.data[0].name").value("백엔드"))
            .andExpect(jsonPath("$.data[0].attr1").value("200000"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"attr2\":null")))
            .andExpect(jsonPath("$.data.data").doesNotExist());
    }
    @Test void existingEmptyGroupIsAnEmptyArray() throws Exception {
        when(mapper.codeOptions("JOB")).thenReturn(List.of());
        mvc.perform(get("/api/codes/JOB")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void unknownGroupIs404() throws Exception {
        mvc.perform(get("/api/codes/UNKNOWN")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper, never()).codeOptions(anyString());
    }
    @Test void groupLengthIsValidatedBeforeSql() throws Exception {
        mvc.perform(get("/api/codes/" + "A".repeat(31))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("group"));
        verifyNoInteractions(mapper);
    }
    @Test void menuRowsBecomeOrderedTwoLevelTreeWithoutInventedScreens() throws Exception {
        when(mapper.menus()).thenReturn(List.of(
            new MenuRow(2, null, "인력", null, "users", true),
            new MenuRow(1, null, "시스템", null, "settings", true),
            new MenuRow(12, 2L, "인력현황", "UI-12", null, true),
            new MenuRow(27, 1L, "공통코드", "UI-27", null, false)));
        mvc.perform(get("/api/menus")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].menuId").value(2))
            .andExpect(jsonPath("$.data[0].children[0].screenId").value("UI-12"))
            .andExpect(jsonPath("$.data[1].children[0].mobile").value(false))
            .andExpect(jsonPath("$.data[1].children[0].children").isEmpty())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"screenId\":null")));
    }
    @Test void inactiveParentsDoNotPromoteOrphanChildren() throws Exception {
        when(mapper.menus()).thenReturn(List.of(new MenuRow(27, 1L, "공통코드", "UI-27", null, false)));
        mvc.perform(get("/api/menus")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void monthCloseReturnsSparseRowsWithoutExpansion() throws Exception {
        when(mapper.monthClose("2026-09", "2026-11")).thenReturn(List.of(new MonthClose("2026-09", "CLOSED"), new MonthClose("2026-10", "OPEN")));
        mvc.perform(get("/api/month-close").param("from", "2026-09").param("to", "2026-11"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].month").value("2026-09")).andExpect(jsonPath("$.data[0].status").value("CLOSED"))
            .andExpect(jsonPath("$.data[1].status").value("OPEN"));
    }
    @Test void completeBoundaryRangeDoesNotExpandMonths() throws Exception {
        when(mapper.monthClose("0001-01", "9999-12")).thenReturn(List.of());
        mvc.perform(get("/api/month-close").param("from", "0001-01").param("to", "9999-12"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        verify(mapper).monthClose("0001-01", "9999-12");
    }
    @ParameterizedTest @ValueSource(strings = {"2026-9", "2026-00", "2026-13", "0000-01", "2026-09-01", " 2026-09"})
    void invalidFromIs400(String from) throws Exception {
        mvc.perform(get("/api/month-close").param("from", from).param("to", "2026-10"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("from"));
        verifyNoInteractions(mapper);
    }
    @Test void invalidToIs400() throws Exception {
        mvc.perform(get("/api/month-close").param("from", "2026-09").param("to", "2026-13"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("to"));
    }
    @Test void reversedRangeIs400BeforeSql() throws Exception {
        mvc.perform(get("/api/month-close").param("from", "2026-11").param("to", "2026-09"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("from"));
        verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings = {"from", "to"})
    void missingRangeParameterIs400(String missing) throws Exception {
        mvc.perform(get("/api/month-close").param(missing.equals("from") ? "to" : "from", "2026-10"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(missing));
    }
    @Test void effectiveParameterIsOneTypedRowNotNestedEnvelope() throws Exception {
        when(mapper.effectiveParam("VAT_RATE", "2026-10")).thenReturn(new OperatingParam("VAT_RATE", "2000-01", "부가세율", new BigDecimal("10.0000"), null, "%", "계산"));
        mvc.perform(get("/api/params/VAT_RATE").param("month", "2026-10")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.oprt_std_id").value("VAT_RATE")).andExpect(jsonPath("$.data.num_val").value(10))
            .andExpect(jsonPath("$.data.aply_bgn_ym").value("2000-01"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"txt_val\":null")))
            .andExpect(jsonPath("$.data.data").doesNotExist());
    }
    @Test void parameterWithoutApplicableRowIs404() throws Exception {
        mvc.perform(get("/api/params/VAT_RATE").param("month", "0001-01"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void unknownParameterIs404() throws Exception {
        mvc.perform(get("/api/params/UNKNOWN").param("month", "2026-10")).andExpect(status().isNotFound());
    }
    @Test void invalidParameterMonthIs400() throws Exception {
        mvc.perform(get("/api/params/VAT_RATE").param("month", "2026-13"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(mapper);
    }
    @Test void missingParameterMonthIs400() throws Exception {
        mvc.perform(get("/api/params/VAT_RATE")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
    }
    @ParameterizedTest @ValueSource(strings = {"/api/menus", "/api/codes/JOB", "/api/month-close?from=2026-09&to=2026-10", "/api/params/VAT_RATE?month=2026-10"})
    void databaseFailuresAreSanitizedAndTraced(String path) throws Exception {
        when(mapper.menus()).thenThrow(new IllegalStateException("private sql details"));
        when(mapper.codeOptions(anyString())).thenThrow(new IllegalStateException("private sql details"));
        when(mapper.monthClose(anyString(), anyString())).thenThrow(new IllegalStateException("private sql details"));
        when(mapper.effectiveParam(anyString(), anyString())).thenThrow(new IllegalStateException("private sql details"));
        var result = mvc.perform(get(path).header("X-Request-Id", "untrusted"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("SERVER_ERROR")).andExpect(jsonPath("$.error").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private sql")))).andReturn();
        String id = result.getResponse().getHeader("X-Request-Id");
        assertThat(id).matches("[a-f0-9-]{36}");
        assertThat(result.getResponse().getContentAsString()).contains(id);
    }
    @Test void reusedHealthRemainsPublicAndHasNoInventedData() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(header().exists("X-Request-Id"));
    }
}
