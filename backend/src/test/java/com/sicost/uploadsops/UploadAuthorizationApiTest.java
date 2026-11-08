package com.sicost.uploadsops;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.security.SecurityModels.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UploadController.class)
@Import({SecurityConfiguration.class,AuthorizationService.class,PermissionService.class,CurrentUser.class,
    RequestIdFilter.class,ApiExceptionHandler.class,UploadAuthorizationApiTest.Time.class})
class UploadAuthorizationApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"),SessionService.SEOUL); }
    }
    @Autowired MockMvc mvc;
    @MockBean UploadService service;
    @MockBean UploadReviewAdapter adapter;
    @MockBean SecurityStateMapper security;
    @MockBean SessionService sessions;
    Cookie cookie;
    Long actor;
    @BeforeEach void setup() {
        actor=Math.abs(UUID.randomUUID().getMostSignificantBits()%1000000000L)+1;
        String raw=AuthenticationProof.randomId();cookie=new Cookie("JSESSIONID",raw);
        when(sessions.authenticate(raw)).thenReturn(new SecurityPrincipal(actor,"시험 사용자",List.of(1L),AuthenticationProof.digest(raw),false));
        when(sessions.expiresAt(any())).thenReturn(Instant.parse("2026-11-08T01:30:00Z"));
        when(security.account(actor)).thenReturn(new Account(actor,"시험 사용자",null,"ACTIVE",null,false));
        allow("UI-02","YNNNYNN");
    }
    void allow(String screen,String actions) {
        when(security.permissions(actor)).thenReturn(List.of(new Permission(1L,"시험 역할",DataScope.ALL,false,false,false,screen,actions,false,false)));
    }
    MockMultipartFile file() { return new MockMultipartFile("file","시험.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",new byte[]{1}); }
    @Test void personnelValidationDoesNotRequireUnrelatedProjectPermission() throws Exception {
        when(service.validatePersonnel(eq("B.8"),isNull(),any())).thenReturn(new PersonnelUploadModels.Result(71L,"B.8",null,1,0,0,true,0,null,null,true));
        mvc.perform(multipart("/api/uploads/validate").file(file()).param("form_type","B.8").cookie(cookie).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.upload_id").value(71));
        verify(service).validatePersonnel(eq("B.8"),isNull(),any());
    }
    @Test void inquiryDoesNotGrantUploadAndCsrfStillApplies() throws Exception {
        allow("UI-02","YNNNNNN");
        mvc.perform(multipart("/api/uploads/validate").file(file()).param("form_type","B.8").cookie(cookie).with(csrf()))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
        allow("UI-02","YNNNYNN");
        mvc.perform(multipart("/api/uploads/validate").file(file()).param("form_type","B.8").cookie(cookie))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"B.7","B.8","B.1"})
    void templateUsesItsOwnScreenDownload(String form) throws Exception {
        allow(form.equals("B.7")?"UI-15":"UI-02","YNNNNYN");
        when(service.template(form)).thenReturn(new UploadModels.UploadFile("template.xlsx",new byte[]{1,2}));
        mvc.perform(get("/api/uploads/templates/"+form).cookie(cookie)).andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(content().bytes(new byte[]{1,2}));
        verify(service).template(form);
    }
    @Test void partnerValidationDoesNotRequireProjectUpload() throws Exception {
        allow("UI-15","YNNNYNN");
        when(service.validate(eq("B.7"),any())).thenReturn(new UploadModels.UploadValidationResult("시험.xlsx",null,null,1,1,0,0,List.of()));
        mvc.perform(multipart("/api/uploads/B.7/validate").file(file()).cookie(cookie).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalRows").value(1));
    }
    @Test void adapterReviewAndDownloadUseServerResolvedTarget() throws Exception {
        long id=actor+1;
        when(adapter.supports(id)).thenReturn(true);
        when(adapter.permissionKey("upload")).thenReturn("UI-13.upload");
        when(adapter.permissionKey("download")).thenReturn("UI-13.download");
        allow("UI-13","YNNNYNN");
        when(adapter.rows(eq(id),isNull(),any())).thenReturn(List.of(Map.of("row",1)));
        mvc.perform(get("/api/uploads/"+id+"/rows").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].row").value(1));
        mvc.perform(get("/api/uploads/"+id+"/errors").cookie(cookie)).andExpect(status().isForbidden());
        verify(adapter,never()).errors(anyLong());
        allow("UI-13","YNNNNYN");
        when(adapter.errors(id)).thenReturn(new UploadModels.UploadFile("errors.xlsx",new byte[]{3}));
        mvc.perform(get("/api/uploads/"+id+"/errors").cookie(cookie)).andExpect(status().isOk()).andExpect(content().bytes(new byte[]{3}));
        verifyNoInteractions(service);
    }
    @Test void anonymousRequestsNeverReachUploadService() throws Exception {
        mvc.perform(get("/api/uploads/templates/B.8")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/uploads/validate").file(file()).param("form_type","B.8").with(csrf()))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(service,adapter);
    }
}
