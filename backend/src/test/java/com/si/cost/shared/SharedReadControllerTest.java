package com.si.cost.shared;

import com.si.cost.common.api.*;
import com.si.cost.common.user.DataScope;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import static com.si.cost.shared.SharedReadModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {SharedReadController.class, HealthController.class})
@Import({SharedReadService.class, RequestIdFilter.class, ApiExceptionHandler.class})
class SharedReadControllerTest {
    @Autowired MockMvc mvc;
    @MockBean SharedReadMapper mapper;

    @Test void approvedFixtureIsLoadedFromClasspath() throws Exception {
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("operationId: getMenus", "operationId: getMonthClose", "operationId: getCodeOptions",
                            "operationId: getEffectiveParam", "operationId: getHealth");
        }
    }
    @Test void menusArePublicTwoLevelOrderedTreesWithNullableFields() throws Exception {
        when(mapper.menus(DataScope.ALL)).thenReturn(List.of(
                new MenuRow(2, null, "기준정보", null, "db", true),
                new MenuRow(12, 2L, "개발자 풀", "UI-02", null, true),
                new MenuRow(10, null, "시스템", null, "gear", true),
                new MenuRow(41, 10L, "공통코드 관리", "UI-27", null, false)));
        mvc.perform(get("/api/menus").header("X-Request-Id", "menus-1"))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", "menus-1"))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
                .andExpect(jsonPath("$.data.success").value(true)).andExpect(jsonPath("$.data.meta").isMap())
                .andExpect(jsonPath("$.data.data", hasSize(2)))
                .andExpect(jsonPath("$.data.data[0].menuId").value(2))
                .andExpect(jsonPath("$.data.data[0].screenId").value(nullValue()))
                .andExpect(jsonPath("$.data.data[0].children[0].screenId").value("UI-02"))
                .andExpect(jsonPath("$.data.data[1].children[0].mobile").value(false))
                .andExpect(jsonPath("$.data.data[1].children[0].children").isEmpty());
        verify(mapper).menus(DataScope.ALL);
    }
    @Test void emptyMenuIsAnArrayNotAPage() throws Exception {
        when(mapper.menus(DataScope.ALL)).thenReturn(List.of());
        mvc.perform(get("/api/menus")).andExpect(status().isOk()).andExpect(jsonPath("$.data.data").isArray())
                .andExpect(jsonPath("$.data.data").isEmpty());
    }
    @Test void codeOptionsExposeOnlyApprovedFieldsIncludingNullAttributes() throws Exception {
        when(mapper.groupExists("JOB")).thenReturn(true);
        when(mapper.codeOptions("JOB", DataScope.ALL)).thenReturn(List.of(new CodeOption("BE", "백엔드", "200000", null, null)));
        mvc.perform(get("/api/codes/JOB")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[0].code").value("BE"))
                .andExpect(jsonPath("$.data.data[0].name").value("백엔드"))
                .andExpect(jsonPath("$.data.data[0].attr1").value("200000"))
                .andExpect(jsonPath("$.data.data[0].attr2").value(nullValue()))
                .andExpect(jsonPath("$.data.data[0].attr3").value(nullValue()))
                .andExpect(jsonPath("$.data.data[0].use_yn").doesNotExist());
        verify(mapper).codeOptions("JOB", DataScope.ALL);
    }
    @Test void existingGroupWithoutActiveCodesReturnsEmptyArray() throws Exception {
        when(mapper.groupExists("JOB")).thenReturn(true);
        when(mapper.codeOptions("JOB", DataScope.ALL)).thenReturn(List.of());
        mvc.perform(get("/api/codes/JOB")).andExpect(status().isOk()).andExpect(jsonPath("$.data.data").isEmpty());
    }
    @Test void unknownGroupIsNotFound() throws Exception {
        mvc.perform(get("/api/codes/UNKNOWN")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper, never()).codeOptions(anyString(), any());
    }
    @Test void overlongGroupIsInvalidBeforeQuery() throws Exception {
        mvc.perform(get("/api/codes/" + "A".repeat(31))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("group"));
        verifyNoInteractions(mapper);
    }
    @Test void monthRangeKeepsStoredStatusesAndFillsUnregisteredMonthsAsOpen() throws Exception {
        when(mapper.monthClose("2026-09", "2026-11", DataScope.ALL))
                .thenReturn(List.of(new MonthClose("2026-09", "CLOSED"), new MonthClose("2026-10", "OPEN")));
        mvc.perform(get("/api/month-close").param("from", "2026-09").param("to", "2026-11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data", hasSize(3)))
                .andExpect(jsonPath("$.data.data[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.data.data[1].status").value("OPEN"))
                .andExpect(jsonPath("$.data.data[2].month").value("2026-11"))
                .andExpect(jsonPath("$.data.data[2].status").value("OPEN"));
    }
    @ParameterizedTest @ValueSource(strings = {"2026-1", "2026-13", "0000-01", "2026-00", "2026-10-01"})
    void invalidFromHasSingularFieldAndMatchingRequestId(String value) throws Exception {
        mvc.perform(get("/api/month-close").param("from", value).param("to", "2026-12").header("X-Request-Id", "invalid-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.field").value("from")).andExpect(jsonPath("$.requestId").value("invalid-1"))
                .andExpect(jsonPath("$.fields").doesNotExist()).andExpect(jsonPath("$.error").doesNotExist());
        verifyNoInteractions(mapper);
    }
    @Test void reversedRangeIsRejectedBeforeQuery() throws Exception {
        mvc.perform(get("/api/month-close").param("from", "2026-11").param("to", "2026-09"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("to"));
        verifyNoInteractions(mapper);
    }
    @Test void invalidToIsRejectedBeforeQuery() throws Exception {
        mvc.perform(get("/api/month-close").param("from", "2026-01").param("to", "0000-12"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("to"));
    }
    @Test void missingRangeInputIs400() throws Exception {
        mvc.perform(get("/api/month-close").param("from", "2026-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("to"));
    }
    @Test void fullCalendarRangeIncludesBothBoundaryMonths() throws Exception {
        when(mapper.monthClose("0001-01", "9999-12", DataScope.ALL)).thenReturn(List.of());
        mvc.perform(get("/api/month-close").param("from", "0001-01").param("to", "9999-12"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data[0].month").value("0001-01"))
                .andExpect(jsonPath("$.data.data[*].month", hasItem("9999-12")));
    }
    @Test void effectiveParamPreservesNumericZeroAndLiteralSchemaFieldNames() throws Exception {
        when(mapper.effectiveParam("VAT_RATE", "2026-10", DataScope.ALL))
                .thenReturn(new EffectiveParam("VAT_RATE", "2000-01", "부가세율", BigDecimal.ZERO, null, "%", "기준"));
        mvc.perform(get("/api/params/VAT_RATE").param("month", "2026-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data.oprt_std_id").value("VAT_RATE"))
                .andExpect(jsonPath("$.data.data.aply_bgn_ym").value("2000-01"))
                .andExpect(jsonPath("$.data.data.num_val").value(0)).andExpect(jsonPath("$.data.data.txt_val").value(nullValue()))
                .andExpect(jsonPath("$.data.data.unit_nm").value("%"));
        verify(mapper).effectiveParam("VAT_RATE", "2026-10", DataScope.ALL);
    }
    @Test void effectiveTextParamIsNotCoercedIntoNumber() throws Exception {
        when(mapper.effectiveParam("AR_AGING_DAYS", "2026-10", DataScope.ALL))
                .thenReturn(new EffectiveParam("AR_AGING_DAYS", "2000-01", "구간", null, "30,60,90", "일", null));
        mvc.perform(get("/api/params/AR_AGING_DAYS").param("month", "2026-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data.num_val").value(nullValue()))
                .andExpect(jsonPath("$.data.data.txt_val").value("30,60,90"));
    }
    @Test void unknownKeyIs404() throws Exception {
        mvc.perform(get("/api/params/UNKNOWN").param("month", "2026-10"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("요청한 데이터를 찾을 수 없습니다."));
    }
    @Test void keyWithoutApplicableVersionIs404() throws Exception {
        when(mapper.effectiveParam("VAT_RATE", "0001-01", DataScope.ALL)).thenReturn(null);
        mvc.perform(get("/api/params/VAT_RATE").param("month", "0001-01")).andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings = {"0000-01", "2026-13", "2026-9"})
    void invalidEffectiveMonthIs400(String month) throws Exception {
        mvc.perform(get("/api/params/VAT_RATE").param("month", month))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(mapper);
    }
    @Test void missingEffectiveMonthIs400() throws Exception {
        mvc.perform(get("/api/params/VAT_RATE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("month"));
    }
    @ParameterizedTest @ValueSource(strings = {"/api/menus", "/api/codes/JOB", "/api/month-close?from=2026-01&to=2026-02", "/api/params/VAT_RATE?month=2026-10"})
    void databaseFailureNeverLeaksSql(String path) throws Exception {
        when(mapper.menus(any())).thenThrow(new IllegalStateException("SELECT secret FROM internal"));
        when(mapper.groupExists(anyString())).thenThrow(new IllegalStateException("SELECT secret FROM internal"));
        when(mapper.monthClose(anyString(), anyString(), any())).thenThrow(new IllegalStateException("SELECT secret FROM internal"));
        when(mapper.effectiveParam(anyString(), anyString(), any())).thenThrow(new IllegalStateException("SELECT secret FROM internal"));
        mvc.perform(get(path).header("X-Request-Id", "read-error"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").value("read-error"))
                .andExpect(jsonPath("$.message").value("처리 중 오류가 발생했습니다."))
                .andExpect(content().string(not(containsString("SELECT"))));
    }
    @Test void healthReusesFoundationSingleEnvelope() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
