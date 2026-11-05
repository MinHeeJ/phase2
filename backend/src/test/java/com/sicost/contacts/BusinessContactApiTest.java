package com.sicost.contacts;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import static com.sicost.common.security.AccessPolicyModels.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BusinessContactApiTest {
    BusinessContactMapper mapper;
    PermissionService permissions;
    MockMvc mvc;
    @BeforeEach void setup() {
        mapper=mock(BusinessContactMapper.class);permissions=mock(PermissionService.class);
        CurrentUser user=mock(CurrentUser.class);when(user.userId()).thenReturn(417L);
        when(permissions.scope(anyString())).thenReturn(new AuthorizedScope(417L,null,List.of(),List.of(),
            List.of(new Grant(4L,DataScope.ALL,false,false,false,false,"영업"))));
        when(mapper.allowed(any())).thenReturn(true);
        mvc=MockMvcBuilders.standaloneSetup(new BusinessContactController(new BusinessContactService(mapper,permissions,user,
            Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"),ZoneId.of("Asia/Seoul")))))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @Test void developerContactUsesPurposeAndAuditsPermittedRead()throws Exception {
        when(mapper.developer(any())).thenReturn(new HashMap<>(Map.of("id",731L,"masked",false)));
        mvc.perform(get("/api/developers/731/contact").param("purpose","STAFF_OPERATION"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(731)).andExpect(jsonPath("$.data.masked").value(false));
        verify(mapper).audit(argThat(q->"STAFF_OPERATION".equals(q.get("purpose")) && Long.valueOf(417).equals(q.get("userId"))));
    }
    @Test void developerContactMissingScopeIs404AndDoesNotReadContact()throws Exception {
        when(mapper.allowed(any())).thenReturn(false);
        mvc.perform(get("/api/developers/731/contact").param("purpose","STAFF_OPERATION"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        verify(mapper,never()).developer(any());verify(mapper,never()).audit(any());
    }
    @Test void partnerMaskedContactSuppressesValuesAndDoesNotAuditDisclosure()throws Exception {
        when(mapper.partner(any())).thenReturn(new HashMap<>(Map.of("id",931L,"masked",true)));
        mvc.perform(get("/api/partners/931/contact").param("purpose","CONTRACT_SALES"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.masked").value(true))
            .andExpect(jsonPath("$.data.phone").isEmpty()).andExpect(jsonPath("$.data.email").isEmpty());
        verify(mapper,never()).audit(any());
    }
    @Test void partnerContactInvalidPurposeIsForbiddenBeforeDatabaseRead()throws Exception {
        mvc.perform(get("/api/partners/931/contact").param("purpose","UNKNOWN"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(mapper);
    }
    @Test void requiredPurposeIsValidatedByMvc()throws Exception {
        mvc.perform(get("/api/developers/731/contact")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(mapper);
    }
}
