package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.admin.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
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
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=AdminController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,AdminService.class,CurrentUser.class,PermissionService.class,AuthenticatedAuditActorTest.Time.class})
class AuthenticatedAuditActorTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),SecurityTime.SEOUL);} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AdminMapper mapper;
    @MockBean SecurityPermissionMapper security;
    final SecurityPrincipal actor=AuthenticatedTestSupport.principal(13L,List.of(1L),DataScope.ALL);
    @BeforeEach void permissions() {
        when(security.grants(any())).thenReturn(List.of(new SecurityPermissionMapper.GrantRow(1L,"ALL",true,true,null)));
        when(security.parts(anyLong())).thenReturn(List.of());when(security.projects(anyLong())).thenReturn(List.of());
    }
    @Test void anonymousReadIs401BeforeAnyBusinessQuery() throws Exception {
        mvc.perform(get("/api/admin/code-groups")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(mapper,security);
    }
    @Test void loggedInWriteUsesCurrentActorAndPreservesExistingEnvelope() throws Exception {
        mvc.perform(post("/api/admin/code-groups").principal(actor).contentType("application/json")
            .content(json.writeValueAsString(Map.of("cd_grp_id","SYNTHETIC_GROUP","cd_grp_nm","합성 시험 그룹"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap());
        verify(mapper).createGroup(argThat(w->w.actor().equals(13L)));
        verify(mapper).appendHistory(argThat(w->w.actor().equals(13L)&&w.type().equals("ADD")));
    }
    @Test void revokedGrantIsQueriedAgainAndDoesNotWrite() throws Exception {
        when(security.grants(any())).thenReturn(List.of());
        mvc.perform(post("/api/admin/code-groups").principal(actor).contentType("application/json")
            .content(json.writeValueAsString(Map.of("cd_grp_id","SYNTHETIC_GROUP","cd_grp_nm","합성 시험 그룹"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(mapper);
    }
    @Test void queryAndDatabaseTotalKeepExistingBusinessValues() throws Exception {
        when(mapper.groups(any())).thenReturn(List.of(new AdminModels.CodeGroup("JOB","직무","BIZ",null,null,null,1,"Y",2)));
        when(mapper.groupCount()).thenReturn(36L);
        mvc.perform(get("/api/admin/code-groups").principal(actor)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(36)).andExpect(jsonPath("$.data.items[0].cd_grp_id").value("JOB"));
        verify(security).grants(argThat(q->q.userId()==13L&&q.actionColumn().equals("inq_auth_yn")&&q.screenId().equals("UI-27")));
    }
    @Test void historicalNullActorRemainsNullInReadProjection() throws Exception {
        when(mapper.history(any())).thenReturn(List.of(new AdminModels.CodeChange(LocalDateTime.of(2026,10,1,9,0),"JOB","직무",null,"ADD","ADD",null,"직무",null)));
        when(mapper.historyCount(any())).thenReturn(1L);
        mvc.perform(get("/api/admin/code-history").principal(actor)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].chg_user_id").isEmpty());
        verify(mapper,never()).appendHistory(any());
    }
    @ParameterizedTest @ValueSource(strings={"untrusted","malicious\tvalue"})
    void responseRequestIdNeverCopiesClientInput(String value) throws Exception {
        var response=mvc.perform(get("/api/admin/code-groups").header("X-Request-Id",value)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.requestId").isNotEmpty()).andReturn().getResponse();
        assertThat(response.getHeader("X-Request-Id")).matches("[a-f0-9-]{36}").isNotEqualTo(value);
        assertThat(response.getContentAsString()).doesNotContain(value);
    }
}
