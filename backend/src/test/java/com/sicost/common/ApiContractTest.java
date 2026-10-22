package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sicost.common.api.*;
import com.sicost.common.health.HealthController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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

@WebMvcTest(controllers = HealthController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ApiContractTest.Examples.class})
class ApiContractTest {
    @Autowired MockMvc mvc;

    @RestController
    static class Examples {
        record Input(@NotBlank(message = "이름을 입력하세요.") String name) {}
        @PostMapping("/__test/input") ApiResponse<Void> input(@Valid @RequestBody Input input) { return ApiResponse.ok(); }
        @GetMapping("/__test/missing") void missing() { throw ApiException.notFound("항목이 없습니다."); }
        @GetMapping("/__test/conflict/{code}") void conflict(@PathVariable String code) { throw ApiException.conflict(code, "충돌", "use_yn"); }
        @GetMapping("/__test/failure") void failure() { throw new IllegalStateException("private database details"); }
        @GetMapping("/__test/array") ApiResponse<List<String>> array() { return ApiResponse.ok(List.of("value")); }
        @GetMapping("/__test/page") ApiResponse<PageResult<String>> page() { return ApiResponse.ok(new PageResult<>(List.of("value"), 25, 1, 20)); }
    }
    @Test void publicHealthMatchesFixtureWithoutBusinessData() throws Exception {
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).contains("operationId: getHealth");
        }
        var result = mvc.perform(get("/api/health")).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.data").doesNotExist()).andReturn();
        assertThat(result.getResponse().getHeader("X-Request-Id")).matches("[a-f0-9-]{36}");
    }
    @Test void requestIdIsFreshAndDoesNotReflectCaller() throws Exception {
        var first = mvc.perform(get("/api/health").header("X-Request-Id", "caller-controlled")).andReturn();
        var second = mvc.perform(get("/api/health")).andReturn();
        assertThat(first.getResponse().getHeader("X-Request-Id")).isNotEqualTo("caller-controlled").isNotEqualTo(second.getResponse().getHeader("X-Request-Id"));
    }
    @Test void validationIsFlatAndHeaderMatchesError() throws Exception {
        var result = mvc.perform(post("/__test/input").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("name")).andExpect(jsonPath("$.message").value("이름을 입력하세요."))
            .andExpect(jsonPath("$.error").doesNotExist()).andReturn();
        mvc.perform(get("/__test/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        String id = result.getResponse().getHeader("X-Request-Id");
        assertThat(result.getResponse().getContentAsString()).contains(id);
    }
    @Test void malformedJsonIs400() throws Exception {
        mvc.perform(post("/__test/input").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
    @ParameterizedTest @ValueSource(strings = {"DUPLICATE_CODE", "SYSTEM_CODE_LOCKED", "CLOSED_MONTH"})
    void distinctConflictsRemain409(String code) throws Exception {
        mvc.perform(get("/__test/conflict/" + code)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }
    @Test void serverErrorsDoNotLeakInternals() throws Exception {
        mvc.perform(get("/__test/failure")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("SERVER_ERROR")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
    }
    @Test void arraysAndPagesAreNotDoubleWrapped() throws Exception {
        mvc.perform(get("/__test/array")).andExpect(jsonPath("$.data[0]").value("value")).andExpect(jsonPath("$.data.data").doesNotExist());
        mvc.perform(get("/__test/page")).andExpect(jsonPath("$.data.total").value(25)).andExpect(jsonPath("$.data.size").value(20));
    }
}
