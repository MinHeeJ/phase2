package com.sicost.notifications;

import com.sicost.authentication.AuthenticationMapper;
import com.sicost.businesscontacts.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.authentication.AuthenticationService.values;

class NotificationContactApiTest {
    NotificationMapper notifications;
    BusinessContactMapper contacts;
    AuthenticationMapper audit;
    PermissionService permissions;
    MockMvc mvc;
    @BeforeEach void setup() {
        notifications=mock(NotificationMapper.class);contacts=mock(BusinessContactMapper.class);audit=mock(AuthenticationMapper.class);permissions=mock(PermissionService.class);
        var current=mock(CurrentUser.class);when(current.userId()).thenReturn(101L);var clock=Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"),SecurityTime.SEOUL);
        mvc=MockMvcBuilders.standaloneSetup(new NotificationController(new NotificationService(notifications,current,clock)),new BusinessContactController(new BusinessContactService(contacts,permissions,current,audit,clock))).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @Test void authorizedListAndTotalUseIdenticalRecipientAndFilter() throws Exception {
        when(notifications.list(any())).thenReturn(List.of(values("id",29L,"title","허용 알림","read",false)));
        when(notifications.count(any())).thenReturn(2L);
        mvc.perform(get("/api/notifications").param("unreadOnly","true")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].title").value("허용 알림"));
        var query=org.mockito.ArgumentCaptor.forClass(Map.class);verify(notifications).list(query.capture());verify(notifications).count(query.getValue());org.assertj.core.api.Assertions.assertThat(query.getValue().get("userId")).isEqualTo(101L);verify(notifications,never()).read(any());
    }
    @Test void unreadCountUsesCurrentRecipientAndNeverMarksRead() throws Exception {
        when(notifications.count(any())).thenReturn(3L);
        mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unreadCount").value(3));
        verify(notifications).count(argThat(q -> Long.valueOf(101).equals(q.get("userId")) && Boolean.TRUE.equals(q.get("unreadOnly"))));
        verify(notifications,never()).read(any());
    }
    @Test void notificationDetailChecksOwnershipAndReturnsSafeBodyWithoutMutation() throws Exception {
        when(notifications.owned(any())).thenReturn(values("id",29L));
        when(notifications.detail(any())).thenReturn(values("id",29L,"title","허용 알림","read",false));
        mvc.perform(get("/api/notifications/29")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(29)).andExpect(jsonPath("$.data.title").value("허용 알림"))
            .andExpect(jsonPath("$.data.read").value(false));
        verify(notifications).detail(argThat(q -> Long.valueOf(101).equals(q.get("userId")) && Long.valueOf(29).equals(q.get("id"))));
        verify(notifications,never()).read(any());
    }
    @Test void missingNotificationDetailDoesNotQueryContent() throws Exception {
        mvc.perform(get("/api/notifications/99")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.data").doesNotExist());
        verify(notifications,never()).detail(any());
    }
    @Test void foreignNotificationIs404WithoutContentOrMutation() throws Exception {
        mvc.perform(patch("/api/notifications/29/read")).andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());verify(notifications,never()).detail(any());verify(notifications,never()).read(any());
    }
    @Test void ownedLostAccessIs403AndOriginalReadStateIsPreserved() throws Exception {
        when(notifications.owned(any())).thenReturn(values("id",29L));
        mvc.perform(patch("/api/notifications/29/read")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));verify(notifications,never()).read(any());
    }
    @Test void readAllDelegatesCurrentRecipientPredicateAndReturnsActualCount() throws Exception {
        when(notifications.readAll(any())).thenReturn(2);when(notifications.count(any())).thenReturn(0L);
        mvc.perform(post("/api/notifications/read-all")).andExpect(status().isOk()).andExpect(jsonPath("$.data.updatedCount").value(2)).andExpect(jsonPath("$.data.unreadCount").value(0));verify(notifications).readAll(argThat(q -> q.get("userId").equals(101L)));
    }
    @Test void businessContactIncludesNoAccountFieldsAndAuditsPurposeNotContact() throws Exception {
        when(contacts.developer(any())).thenReturn(values("phone","010-0000-0000","email","trial@example.invalid","masked",false));
        mvc.perform(get("/api/developers/31/contact").param("purpose","STAFF_OPERATION")).andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value("010-0000-0000")).andExpect(jsonPath("$.data.loginId").doesNotExist()).andExpect(jsonPath("$.data.mfaRequired").doesNotExist());
        verify(audit).audit(argThat(q -> "CONTACT_VIEW".equals(q.get("action")) && "STAFF_OPERATION".equals(q.get("detail")) && !q.containsKey("phone")));
    }
    @Test void partnerContactUsesIndependentTargetAndPurpose() throws Exception {
        when(contacts.partner(any())).thenReturn(values("phone",null,"email",null,"masked",true));
        mvc.perform(get("/api/partners/42/contact").param("purpose","BILLING_RECEIPT_PAYMENT")).andExpect(status().isOk()).andExpect(jsonPath("$.data.masked").value(true));verify(contacts).partner(argThat(q -> q.get("id").equals(42L) && q.get("userId").equals(101L)));verify(contacts,never()).developer(any());
    }
    @Test void unapprovedPurposeNeverReachesContactQuery() throws Exception {
        mvc.perform(get("/api/developers/31/contact").param("purpose","ACCOUNT_CORRECTION")).andExpect(status().isForbidden());verifyNoInteractions(contacts,audit);
    }
    @Test void foreignScopeAndAbsentTargetAreBoth404() throws Exception {
        mvc.perform(get("/api/partners/42/contact").param("purpose","CONTRACT_SALES")).andExpect(status().isNotFound());verifyNoInteractions(audit);
    }
    @Test void partnerSalesContactReturnsOnlyBusinessFieldsAndAuditsSelectedTarget() throws Exception {
        when(contacts.partner(any())).thenReturn(values("phone","02-000-0042","email","partner@example.invalid","masked",false));
        mvc.perform(get("/api/partners/42/contact").param("purpose","CONTRACT_SALES"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data",org.hamcrest.Matchers.aMapWithSize(3)))
            .andExpect(jsonPath("$.data.phone").value("02-000-0042"))
            .andExpect(jsonPath("$.data.email").value("partner@example.invalid"))
            .andExpect(jsonPath("$.data.masked").value(false));
        verify(permissions).can("UI-15.read");
        verify(contacts).partner(argThat(q -> q.get("id").equals(42L) && q.get("userId").equals(101L)
            && q.get("screenId").equals("UI-15") && q.get("purpose").equals("CONTRACT_SALES")));
        verify(audit).audit(argThat(q -> q.get("target").equals("42") && q.get("detail").equals("CONTRACT_SALES")
            && q.get("action").equals("CONTACT_VIEW") && !q.containsKey("phone") && !q.containsKey("email")));
        verify(contacts,never()).developer(any());
    }
    @Test void deniedPartnerMenuCannotReadOrAuditContact() throws Exception {
        doThrow(ApiException.forbidden()).when(permissions).can("UI-15.read");
        mvc.perform(get("/api/partners/42/contact").param("purpose","CONTRACT_SALES"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(contacts,audit);
    }
    @Test void partnerAccountCorrectionPurposeCannotReachBusinessRepository() throws Exception {
        mvc.perform(get("/api/partners/42/contact").param("purpose","ACCOUNT_CORRECTION"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(contacts,audit);
    }
}
