package com.sicost.billings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.security.SecurityModels.*;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.sicost.billings.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BillingController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingService.class, BillingAmountService.class,
    CurrentUser.class, PermissionService.class, AuthorizationService.class, SecurityConfiguration.class, BillingDetailApiTest.Time.class})
class BillingAccessApiTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired BillingService service;
    @Autowired ObjectMapper json;
    @MockBean BillingMapper mapper;
    @MockBean SecurityStateMapper security;
    @MockBean SessionService sessions;
    Cookie cookie;
    SecurityPrincipal principal;
    State current;
    @BeforeEach void setup() {
        String raw = AuthenticationProof.randomId();
        cookie = new Cookie("JSESSIONID", raw);
        principal = new SecurityPrincipal(11L, "재무 담당", List.of(3L), AuthenticationProof.digest(raw), false);
        when(sessions.authenticate(raw)).thenReturn(principal);
        when(security.account(11L)).thenReturn(new Account(11L, "재무 담당", null, "ACTIVE", null, false));
        when(security.parts(11L)).thenReturn(List.of(2L));
        when(security.projects(11L)).thenReturn(List.of(1L));
        grants(permission(3L, "재무 담당", DataScope.ALL, "YYYYYYY"));
        current = BillingDetailApiTest.state("MO", "DRAFT", "2026-10", java.time.LocalDate.of(2026, 10, 10));
        when(mapper.state(7)).thenAnswer(i -> current);
        when(mapper.resource(any())).thenReturn(new Resource(null, 2L, 1L, null));
        when(mapper.lockProject(1)).thenReturn(new Project(1, "MO", "MONTH", "Y"));
        when(mapper.lockBill(7)).thenAnswer(i -> current);
        when(mapper.row(7)).thenAnswer(i -> BillingDetailApiTest.row(current));
        when(mapper.details(7)).thenReturn(List.of(BillingDetailApiTest.line()));
        when(mapper.update(any())).thenAnswer(i -> { current = i.getArgument(0, Write.class).state(); return 1; });
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    Permission permission(long id, String name, DataScope scope, String actions) {
        return new Permission(id, name, scope, false, false, false, "UI-13", actions, false, false);
    }
    void grants(Permission... values) { when(security.permissions(11L)).thenReturn(List.of(values)); }
    org.springframework.test.web.servlet.ResultActions update(String body) throws Exception {
        return mvc.perform(put("/api/billings/7").cookie(cookie).with(csrf().asHeader())
            .contentType("application/json").content(body));
    }
    void noWrite() {
        verify(mapper, never()).update(any()); verify(mapper, never()).updateDetail(any());
        verify(mapper, never()).deleteDetails(anyLong()); verify(mapper, never()).audit(any());
    }
    @Test void anonymousDetailIs401BeforeBusinessRead() throws Exception {
        mvc.perform(get("/api/billings/7")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.requestId").isString());
        verifyNoInteractions(mapper);
    }
    @Test void anonymousUpdateIs401BeforeCsrf() throws Exception {
        mvc.perform(put("/api/billings/7").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized()); verifyNoInteractions(mapper);
    }
    @Test void cookieWithoutCsrfCannotUpdate() throws Exception {
        mvc.perform(put("/api/billings/7").cookie(cookie).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden()); verifyNoInteractions(mapper);
    }
    @Test void authenticatedDetailPreservesSalesAmountsAndDoesNotWrite() throws Exception {
        mvc.perform(get("/api/billings/7").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.billing.sply_amt").value(1000))
            .andExpect(jsonPath("$.data.details[0].untprc_amt").value(2000));
        noWrite();
    }
    @Test void authenticatedUpdatePreservesAttributionAndRecordsBothSnapshotsWithActor() throws Exception {
        update("{\"bill_dt\":\"2026-11-01\",\"vat_amt\":17,\"rmrk_desc\":\"변경\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.atrb_ym").value("2026-10"))
            .andExpect(jsonPath("$.data.details[0].work_ym").value("2026-10"));
        verify(mapper).update(argThat(w -> w.actor().equals(11L) && w.state().vat_amt() == 17));
        verify(mapper).audit(argThat(a -> a.actor().equals(11L) && a.before().contains("\"vat_amt\":100")
            && a.after().contains("\"vat_amt\":17") && a.after().contains("변경")));
    }
    @Test void missingMenuGrantDeniesDetail() throws Exception {
        grants(); mvc.perform(get("/api/billings/7").cookie(cookie)).andExpect(status().isForbidden()); verifyNoInteractions(mapper);
    }
    @Test void missingUpdateGrantDeniesWrite() throws Exception {
        grants(permission(3, "재무 담당", DataScope.ALL, "YNNNNNN"));
        update("{}").andExpect(status().isForbidden()); verifyNoInteractions(mapper);
    }
    @Test void executiveCannotEditEvenWithErroneousWriteGrant() throws Exception {
        grants(permission(2, "경영진", DataScope.ALL, "YYYYYYY"));
        update("{}").andExpect(status().isForbidden()); verifyNoInteractions(mapper);
    }
    @Test void outOfScopeDetailIs404WithoutReadingLinesHistoryOrReceipts() throws Exception {
        grants(permission(4, "영업 파트장", DataScope.PART, "YNNNNNN"));
        when(mapper.resource(any())).thenReturn(new Resource(null, 8L, 9L, null));
        mvc.perform(get("/api/billings/7").cookie(cookie)).andExpect(status().isNotFound());
        verify(mapper, never()).details(anyLong()); verify(mapper, never()).history(anyLong()); noWrite();
    }
    @Test void outOfScopeUpdateHasNoSideEffects() throws Exception {
        grants(permission(3, "재무 담당", DataScope.PROJECT, "YYYYYYY"));
        when(mapper.resource(any())).thenReturn(new Resource(null, 8L, 9L, null));
        update("{\"vat_amt\":17}").andExpect(status().isNotFound()); noWrite();
    }
    @Test void multipleRolesUseUnionOfTheirGrantedScopes() throws Exception {
        grants(permission(4, "영업 파트장", DataScope.PART, "YNNNNNN"), permission(5, "일반 사용자(PM)", DataScope.PROJECT, "YNNNNNN"));
        when(mapper.resource(any())).thenReturn(new Resource(null, 8L, 1L, null));
        mvc.perform(get("/api/billings/7").cookie(cookie)).andExpect(status().isOk());
    }
    @Test void broadReadRoleCannotLendItsScopeToNarrowWriteRole() throws Exception {
        grants(permission(2, "경영진", DataScope.ALL, "YNNNNNN"), permission(3, "재무 담당", DataScope.PART, "YYYYYYY"));
        when(mapper.resource(any())).thenReturn(new Resource(null, 8L, 1L, null));
        update("{\"vat_amt\":17}").andExpect(status().isNotFound()); noWrite();
    }
    @Test void selfScopeRequiresActualOwner() throws Exception {
        grants(permission(5, "일반 사용자(PM)", DataScope.SELF, "YNNNNNN"));
        when(mapper.resource(any())).thenReturn(new Resource(11L, 8L, 9L, null));
        mvc.perform(get("/api/billings/7").cookie(cookie)).andExpect(status().isOk());
    }
    @Test void restrictedPasswordSessionCannotReadBilling() throws Exception {
        when(sessions.authenticate(cookie.getValue())).thenReturn(new SecurityPrincipal(11L, "재무 담당", List.of(3L), principal.sessionHash(), true));
        mvc.perform(get("/api/billings/7").cookie(cookie)).andExpect(status().isForbidden()); verifyNoInteractions(mapper);
    }
    @Test void unknownBillingIs404() throws Exception {
        mvc.perform(get("/api/billings/99").cookie(cookie)).andExpect(status().isNotFound()); noWrite();
    }
    @Test void unknownUpdateIs404() throws Exception {
        mvc.perform(put("/api/billings/99").cookie(cookie).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isNotFound()); noWrite();
    }
    @Test void permissionRevocationAlsoAppliesToDirectServiceCalls() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        grants();
        assertThatThrownBy(() -> service.get(7)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> service.update(7, BillingInput.parse(json.readTree("{}"), false)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        verifyNoInteractions(mapper);
    }
}
