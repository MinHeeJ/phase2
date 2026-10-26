package com.si.cost.common.api;

import com.si.cost.CostApplication;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HealthController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, HealthControllerTest.ErrorController.class})
class HealthControllerTest {
    @Autowired MockMvc mvc;

    @Test void healthMatchesApprovedResponseWithoutAuthentication() throws Exception {
        assertThat(new ClassPathResource("contracts/openapi.yaml").exists()).isTrue();
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
                .andExpect(jsonPath("$.data").doesNotExist()).andExpect(header().exists("X-Request-Id"));
    }
    @Test void safeSuppliedRequestIdIsPreserved() throws Exception {
        mvc.perform(get("/api/health").header("X-Request-Id", "inquiry-123"))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", "inquiry-123"));
    }
    @Test void unsafeRequestIdDoesNotRejectPublicGet() throws Exception {
        var result = mvc.perform(get("/api/health").header("X-Request-Id", "<unsafe>"))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("X-Request-Id"))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    @Test void oversizedRequestIdIsReplaced() throws Exception {
        mvc.perform(get("/api/health").header("X-Request-Id", "a".repeat(101)))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", not("a".repeat(101))));
    }
    @Test void validationUsesSingularFieldAndMatchingRequestId() throws Exception {
        mvc.perform(get("/contract-errors/validation").header("X-Request-Id", "validation-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("입력값을 확인하세요."))
                .andExpect(jsonPath("$.field").value("[0].cd_nm"))
                .andExpect(jsonPath("$.requestId").value("validation-1"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
    @Test void unknownResourcesHave404Envelope() throws Exception {
        mvc.perform(get("/contract-errors/missing"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 데이터를 찾을 수 없습니다."));
    }
    @Test void conflictPreservesBusinessCode() throws Exception {
        mvc.perform(get("/contract-errors/conflict"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYSTEM_CODE_LOCKED"))
                .andExpect(jsonPath("$.message").value("시스템 코드는 사용 중지할 수 없습니다."));
    }
    @Test void unexpectedErrorsNeverExposeSqlOrStackTrace() throws Exception {
        mvc.perform(get("/contract-errors/server").header("X-Request-Id", "server-1"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("처리 중 오류가 발생했습니다."))
                .andExpect(jsonPath("$.requestId").value("server-1"))
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }
    @RestController
    static class ErrorController {
        @GetMapping("/contract-errors/validation") Object validation() { throw BusinessException.invalid("[0].cd_nm"); }
        @GetMapping("/contract-errors/missing") Object missing() { throw BusinessException.notFound(); }
        @GetMapping("/contract-errors/conflict") Object conflict() {
            throw BusinessException.conflict("SYSTEM_CODE_LOCKED", "시스템 코드는 사용 중지할 수 없습니다.", "use_yn");
        }
        @GetMapping("/contract-errors/server") Object server() { throw new IllegalStateException("SELECT private FROM internal"); }
    }
}
