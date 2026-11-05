package com.sicost.notifications;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationApiTest {
    NotificationMapper mapper;CurrentUser user;MockMvc mvc;
    @BeforeEach void setup() {
        mapper=mock(NotificationMapper.class);user=mock(CurrentUser.class);when(user.userId()).thenReturn(924L);
        mvc=MockMvcBuilders.standaloneSetup(new NotificationController(new NotificationService(mapper,user,Clock.system(ZoneId.of("Asia/Seoul")))))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @Test void listAndTotalUseSameCurrentRecipientQuery()throws Exception {
        when(mapper.list(any())).thenReturn(List.of(new HashMap<>(Map.of("id",791L,"title","담당 업무 알림","projectId",548L))));when(mapper.count(any())).thenReturn(1L);
        mvc.perform(get("/api/notifications")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].linkParams.projectId").value(548));
        verify(mapper).list(argThat(q->q.get("userId").equals(924L) && q.get("offset").equals(0L)));
        verify(mapper).count(argThat(q->q.get("userId").equals(924L)));
    }
    @Test void anotherRecipientsIdIs404AndNeverMutated()throws Exception {
        when(mapper.owned(any())).thenReturn(null);
        mvc.perform(patch("/api/notifications/791/read")).andExpect(status().isNotFound());verify(mapper,never()).read(any());
    }
    @Test void revokedOwnNotificationIs403WithoutExposingItsTitle()throws Exception {
        when(mapper.owned(any())).thenReturn(Map.of("id",791L,"title","접근하면 안 되는 제목"));when(mapper.visible(any())).thenReturn(false);
        mvc.perform(get("/api/notifications/791")).andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(patch("/api/notifications/791/read")).andExpect(status().isForbidden());verify(mapper,never()).read(any());
    }
    @Test void readAllUsesCurrentIdentityAndNoClientSuppliedRecipient()throws Exception {
        mvc.perform(post("/api/notifications/read-all")).andExpect(status().isOk());verify(mapper).readAll(argThat(q->q.get("userId").equals(924L)));
    }
    @Test void unreadCountReturnsUncappedDatabaseCount()throws Exception {
        when(mapper.count(any())).thenReturn(138L);
        mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(138));
        verify(mapper).count(argThat(q->Boolean.TRUE.equals(q.get("unreadOnly"))));
    }
}
