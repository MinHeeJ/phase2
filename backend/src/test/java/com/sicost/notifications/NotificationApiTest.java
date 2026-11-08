package com.sicost.notifications;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(NotificationController.class)
@Import({NotificationService.class,SecurityConfiguration.class,PermissionService.class,ApiExceptionHandler.class,RequestIdFilter.class,CurrentUser.class,NotificationApiTest.Time.class})
class NotificationApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"),SessionService.SEOUL);} }
    @Autowired MockMvc mvc;
    @MockBean NotificationMapper mapper;
    @MockBean SessionService sessions;
    @MockBean AuthorizationService authorization;
    Cookie cookie;
    Long actor,id;
    @BeforeEach void setup() {
        actor=Math.abs(UUID.randomUUID().getMostSignificantBits()%1000000000L)+1;id=actor+1;
        String raw=AuthenticationProof.randomId();cookie=new Cookie("JSESSIONID",raw);
        when(sessions.authenticate(raw)).thenReturn(new SecurityPrincipal(actor,"수신자",List.of(5L),AuthenticationProof.digest(raw),false));when(sessions.expiresAt(any())).thenReturn(Instant.parse("2026-11-08T01:30:00Z"));
        when(mapper.own(any())).thenReturn(null);
    }
    Map<String,Object> row(){return Map.of("notificationId",id,"title","업무 확인","message","허용 알림","createdAt","2026-11-08T10:00:00","read",false,"targetScreenId","UI-01","targetJson","{\"projectId\":83,\"untrusted\":\"never forward\"}");}
    @Test void listAndCountShareCurrentRecipientAndGetNeverMarksRead()throws Exception {
        when(mapper.list(any())).thenReturn(List.of(row()));when(mapper.count(any())).thenReturn(100L);
        mvc.perform(get("/api/notifications").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(100)).andExpect(jsonPath("$.data.items[0].target.projectId").value(83)).andExpect(jsonPath("$.data.items[0].target.untrusted").doesNotExist()).andExpect(jsonPath("$.data.items[0].targetJson").doesNotExist());
        mvc.perform(get("/api/notifications/unread-count").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(100));verify(mapper,never()).read(any());verify(mapper,never()).readAll(any());verify(mapper).list(argThat(q->actor.equals(q.get("userId"))&&Integer.valueOf(0).equals(q.get("offset"))));
    }
    @Test void otherOwner404AndOwnRevoked403DoNotMutateReadState()throws Exception {
        mvc.perform(patch("/api/notifications/"+id+"/read").cookie(cookie).with(csrf())).andExpect(status().isNotFound());
        when(mapper.own(any())).thenReturn(row());when(mapper.visible(any())).thenReturn(false);
        mvc.perform(patch("/api/notifications/"+id+"/read").cookie(cookie).with(csrf())).andExpect(status().isForbidden());verify(mapper,never()).read(any());
    }
    @Test void detailIsReadOnlyAndPatchUsesSameRecipientPredicate()throws Exception {
        when(mapper.own(any())).thenReturn(row());when(mapper.visible(any())).thenReturn(true);
        mvc.perform(get("/api/notifications/"+id).cookie(cookie)).andExpect(status().isOk());verify(mapper,never()).read(any());
        mvc.perform(patch("/api/notifications/{id}/read",id).cookie(cookie).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));verify(mapper).read(argThat(q->actor.equals(q.get("userId"))&&id.equals(q.get("id"))));
    }
    @Test void readAllDoesNotAcceptRecipientOverrideAndRequiresCsrf()throws Exception {
        mvc.perform(post("/api/notifications/read-all").cookie(cookie)).andExpect(status().isForbidden());verify(mapper,never()).readAll(any());
        mvc.perform(post("/api/notifications/read-all").cookie(cookie).with(csrf())).andExpect(status().isOk());verify(mapper).readAll(argThat(q->actor.equals(q.get("userId"))));
    }
    @Test void anonymousCannotReadOrMarkNotifications()throws Exception {mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());verifyNoInteractions(mapper);}
}
