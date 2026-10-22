package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.api.ApiExceptionHandler;
import com.sicost.common.api.RequestIdFilter;
import com.sicost.common.health.HealthController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpContractTest {
    private MockMvc mvc;

    @BeforeEach
    void configureHttp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new HealthController(), new ContractController())
                .setControllerAdvice(new ApiExceptionHandler()).setValidator(validator)
                .addFilters(new RequestIdFilter()).build();
    }

    @Test
    void publicHealthHasOnlyApprovedEnvelopeAndRequestId() throws Exception {
        assertThat(new ClassPathResource("contracts/openapi.yaml").exists()).isTrue();
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(content().json("{\"success\":true,\"meta\":{}}", true));
    }

    @Test
    void validRequestIdIsPreserved() throws Exception {
        mvc.perform(get("/api/health").header(RequestIdFilter.HEADER, "client_123-ABC"))
                .andExpect(header().string(RequestIdFilter.HEADER, "client_123-ABC"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad/value", "bad value", "\r\nInjected: yes", "bad\tvalue", "한글", "bad%0d%0avalue", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void unsafeOrOversizeRequestIdIsReplaced(String value) throws Exception {
        var result = mvc.perform(get("/api/health").header(RequestIdFilter.HEADER, value)).andReturn();
        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).matches("[A-Za-z0-9_-]{1,64}").isNotEqualTo(value);
    }

    @Test
    void inputErrorIncludesSingleFieldAndSameRequestId() throws Exception {
        var result = mvc.perform(post("/contract/input").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.field").value("name"))
                .andExpect(jsonPath("$.error").doesNotExist()).andExpect(jsonPath("$.fields").doesNotExist()).andReturn();
        var json = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(json.path("requestId").asText()).isEqualTo(result.getResponse().getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/contract/input").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void missingParameterIs400WithField() throws Exception {
        mvc.perform(get("/contract/required")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
    }

    @Test
    void absentResourceIs404() throws Exception {
        mvc.perform(get("/contract/not-found")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUPLICATE_CODE", "SYSTEM_CODE_LOCKED", "CLOSED_MONTH"})
    void businessConflictIs409(String code) throws Exception {
        mvc.perform(get("/contract/conflict").param("code", code)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void unexpectedErrorDoesNotExposeInternalSqlOrStack() throws Exception {
        var result = mvc.perform(get("/contract/failure")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SELECT", "password", "IllegalStateException");
    }

    @RestController
    static class ContractController {
        record Input(@NotBlank(message = "이름을 입력하세요.") String name) {}
        @PostMapping("/contract/input") public void input(@Valid @RequestBody Input input) {}
        @GetMapping("/contract/required") public void required(@RequestParam String month) {}
        @GetMapping("/contract/not-found") public void absent() { throw ApiException.notFound(); }
        @GetMapping("/contract/conflict") public void conflict(@RequestParam String code) { throw ApiException.conflict(code, "충돌입니다."); }
        @GetMapping("/contract/failure") public void failure() { throw new IllegalStateException("SELECT password FROM sys.user_info"); }
    }
}
