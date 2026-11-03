package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = WorkforcePlanningContractTest.Envelopes.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, CurrentUser.class,
    PermissionService.class, WorkforcePlanningContractTest.Envelopes.class})
class WorkforcePlanningContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CurrentUser actor;
    @Autowired PermissionService permissions;

    // These are shared-envelope probes, not substitutes for another module's production handlers.
    @RestController
    static class Envelopes {
        @PostMapping("/__test/workforce-contract/echo")
        ApiResponse<Map<String,Object>> echo(@RequestBody Map<String,Object> body) { return ApiResponse.ok(body); }
        @GetMapping("/__test/workforce-contract/invalid")
        void invalid() { throw ApiException.badRequest("입력값을 확인하세요.", "needs"); }
        @GetMapping("/__test/workforce-contract/missing")
        void missing() { throw ApiException.notFound("항목이 없습니다."); }
        @PostMapping("/__test/workforce-contract/conflict/{code}")
        void conflict(@PathVariable String code) { throw ApiException.conflict(code, "업무 조건을 확인하세요.", null); }
    }

    @Test void anonymousJsonWritesUse200AndOneDataEnvelope() throws Exception {
        mvc.perform(post("/__test/workforce-contract/echo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"needs\":[],\"win_prj_id\":null}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.needs").isEmpty())
            .andExpect(jsonPath("$.data.data").doesNotExist());
        assertThat(actor.userId()).isNull();
        assertThat(actor.dataScope()).isEqualTo(DataScope.ALL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UI-29.read", "UI-29.create", "UI-29.update", "UI-29.approve", "UI-29.win",
        "UI-29.download", "UI-05.read", "UI-17.read", "UI-28.read", "UI-28.download",
        "UI-33.read", "UI-33.create", "UI-33.update", "UI-33.convert"})
    void permissionSlotsKeepExistingAlwaysAllowPolicy(String key) {
        assertThat(permissions.can(key)).isTrue();
        assertThat(actor.userId()).isNull();
    }

    @Test void validationHasSingularFieldAndServerGeneratedCorrelation() throws Exception {
        var result = mvc.perform(get("/__test/workforce-contract/invalid").header("X-Request-Id", "caller-value"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value("needs")).andExpect(jsonPath("$.fields").doesNotExist())
            .andExpect(jsonPath("$.error").doesNotExist()).andReturn();
        String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(requestId).matches("[a-f0-9-]{36}").isNotEqualTo("caller-value");
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("requestId").asText()).isEqualTo(requestId);
    }

    @Test void malformedInputKeeps400RatherThanAnAuthenticationFailure() throws Exception {
        mvc.perform(post("/__test/workforce-contract/echo").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test void missingResourceKeeps404RatherThanAnAuthenticationFailure() throws Exception {
        mvc.perform(get("/__test/workforce-contract/missing"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPPORTUNITY_CLOSED", "QUOTE_APPROVAL_REQUIRED", "DUPLICATE_TEMP_ASSIGNMENT",
        "ASSIGNMENT_CONFLICT", "PLAN_OUTSIDE_CONTRACT", "ALREADY_CONVERTED"})
    void sharedHandlerPreservesDistinctBusinessConflicts(String code) throws Exception {
        mvc.perform(post("/__test/workforce-contract/conflict/" + code))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(jsonPath("$.fields").doesNotExist());
    }

    @Test void durableSharedErrorSchemaStillAgreesWithProductionRecord() throws Exception {
        Map<?,?> contract;
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract = new Yaml().load(input);
        }
        var components = (Map<?,?>) contract.get("components");
        var schemas = (Map<?,?>) components.get("schemas");
        var error = (Map<?,?>) schemas.get("ApiError");
        var fields = (Map<?,?>) error.get("properties");
        assertThat(fields.keySet().stream().map(Object::toString).toList())
            .containsExactlyInAnyOrder("success", "meta", "code", "message", "field", "requestId");
        assertThat(Arrays.stream(ApiError.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
            .containsExactlyInAnyOrder("success", "meta", "code", "message", "field", "requestId");
    }

    @Test void futureProfitProjectionPreservesUnavailableBaselineAndWarning() {
        var month = new MonthlyProfitReader.Month("2026-11", null, null, List.of("FUTURE_ACTUAL"));
        var data = json.valueToTree(ApiResponse.ok(month)).path("data");
        assertThat(data.path("operatingProfit").isNull()).isTrue();
        assertThat(data.path("warnings").get(0).asText()).isEqualTo("FUTURE_ACTUAL");
    }

    @Test void commonForecastAssertionIgnoresRowOrderButDetectsDivergentRawValues() throws Exception {
        var baseline = json.readTree("{\"items\":[{\"month\":\"2026-11\",\"job_cd\":\"BE\",\"supply_mm\":0.404},{\"month\":\"2026-12\",\"job_cd\":\"BE\",\"supply_mm\":0.405}]}");
        var reordered = baseline.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) reordered.path("items")).removeAll()
            .add(baseline.path("items").get(1)).add(baseline.path("items").get(0));
        WorkforcePlanningAssertions.assertForecastItemsEqual(baseline, reordered);
        var changed = json.readTree("{\"items\":[{\"month\":\"2026-11\",\"job_cd\":\"BE\",\"supply_mm\":0.40},{\"month\":\"2026-12\",\"job_cd\":\"BE\",\"supply_mm\":0.405}]}");
        assertThatThrownBy(() -> WorkforcePlanningAssertions.assertForecastItemsEqual(baseline, changed)).isInstanceOf(AssertionError.class);
    }

    @Test void commonForecastAssertionRejectsDuplicateBuckets() throws Exception {
        var duplicate = json.readTree("{\"items\":[{\"month\":\"2026-11\",\"job_cd\":\"BE\"},{\"month\":\"2026-11\",\"job_cd\":\"BE\"}]}");
        assertThatThrownBy(() -> WorkforcePlanningAssertions.assertForecastItemsEqual(duplicate, duplicate)).isInstanceOf(AssertionError.class);
    }
}
