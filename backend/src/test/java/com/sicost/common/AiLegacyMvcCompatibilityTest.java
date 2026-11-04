package com.sicost.common;

import com.sicost.common.api.*;
import com.sicost.withdrawalsops.NextSlotController;
import com.sicost.withdrawalsops.NextSlotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NextSlotController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class})
class AiLegacyMvcCompatibilityTest {
    @Autowired MockMvc mvc;
    @MockBean NextSlotService service;

    @Test void newAiConfigurationDoesNotInjectAiDependenciesIntoExistingMvcSlices() throws Exception {
        when(service.get(1)).thenThrow(ApiException.notFound("투입이 없습니다."));
        mvc.perform(get("/api/withdrawals/1/next-slots")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND")).andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(service).get(1);
    }
}
