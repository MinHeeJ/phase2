package com.sicost.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.health.HealthController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthenticationFilterTest {
    AuthenticationService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(AuthenticationService.class);var factory=new StaticListableBeanFactory();factory.addBean("authenticationService",service);
        mvc=MockMvcBuilders.standaloneSetup(new AuthenticationController(service),new HealthController()).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter(),new AuthenticationFilter(factory.getBeanProvider(AuthenticationService.class),new ObjectMapper())).build();
    }
    @Test void healthIsPublicAndProtectedMeRejectsAnonymous() throws Exception {
        when(service.session(any())).thenThrow(ApiException.unauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));verify(service,never()).me(any());
    }
    @Test void csrfRejectionHappensBeforeLoginBodyIsProcessed() throws Exception {
        doThrow(ApiException.forbidden()).when(service).verifyCsrf(any(),isNull());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"loginId\":\"person\",\"password\":\"untrusted\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));verify(service,never()).login(any(),any(),any(),any());
    }
    @Test void passwordChangeOnlySessionCannotReadBusinessIdentity() throws Exception {
        when(service.session(any())).thenReturn(java.util.Map.of("passwordChangeRequired",true));
        mvc.perform(get("/api/auth/me")).andExpect(status().isForbidden());verify(service,never()).me(any());
    }
}
