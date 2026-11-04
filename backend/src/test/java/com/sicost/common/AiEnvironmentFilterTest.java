package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.ai.*;
import com.sicost.common.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiEnvironmentFilterTest {
    final MockEnvironment environment = new MockEnvironment();
    MockMvc mvc;
    @RestController static class Boundary {
        @GetMapping("/api/ai/__boundary") ApiResponse<String> ai() { return ApiResponse.ok("allowed"); }
        @GetMapping("/__test/ordinary") ApiResponse<String> ordinary() { return ApiResponse.ok("ordinary"); }
    }
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Boundary()).setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new RequestIdFilter(), new AiEnvironmentFilter(new AiAccessGuard(environment), new ObjectMapper()))
            .build();
    }
    @Test void anonymousInternalRequestReachesHandlerWithoutNewAuthentication() throws Exception {
        mvc.perform(get("/api/ai/__boundary")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value("allowed")).andExpect(header().doesNotExist("Location"));
    }
    @Test void productionRequestIsBlockedWithExistingErrorEnvelopeBeforeHandler() throws Exception {
        environment.setActiveProfiles("production", "test");
        environment.setProperty("sicost.ai.synthetic-test-enabled", "true");
        mvc.perform(get("/api/ai/__boundary")).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(jsonPath("$.fields").doesNotExist())
            .andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void productionDoesNotRetrofitLoginOrDenialsOnOrdinaryRoutes() throws Exception {
        environment.setActiveProfiles("production");
        mvc.perform(get("/__test/ordinary")).andExpect(status().isOk()).andExpect(jsonPath("$.data").value("ordinary"));
    }
}
