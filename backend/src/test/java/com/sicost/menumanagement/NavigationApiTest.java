package com.sicost.menumanagement;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.useraccounts.ManagementMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static com.sicost.authentication.AuthenticationService.values;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NavigationApiTest {
    ManagementMapper mapper;
    PermissionService permissions;
    CurrentUser user;
    MockMvc mvc;
    @BeforeEach void setup() {
        mapper=mock(ManagementMapper.class);permissions=mock(PermissionService.class);user=mock(CurrentUser.class);
        when(mapper.menus()).thenReturn(List.of(values("id",16L,"name","대시보드","screenId","UI-16","useYn","Y","mobileVisible",true)));
        mvc=MockMvcBuilders.standaloneSetup(new NavigationController(new NavigationService(mapper,permissions,user)))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @Test void navigationUsesAuthorizedDatabaseMenusAndDefaultScreen() throws Exception {
        mvc.perform(get("/api/navigation")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.menus[0].screenId").value("UI-16"))
            .andExpect(jsonPath("$.data.dashboardAllowed").value(true))
            .andExpect(jsonPath("$.data.firstAllowedScreenId").value("UI-16"))
            .andExpect(jsonPath("$.data.permissionVersion").isString());
        verify(permissions).can("UI-16.read");verify(mapper).menus();
    }
    @Test void permissionsExcludeDeniedActionsAndDoNotWrite() throws Exception {
        doThrow(ApiException.forbidden()).when(permissions).can("UI-16.download");
        mvc.perform(get("/api/me/permissions")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.permissions[0].screenId").value("UI-16"))
            .andExpect(jsonPath("$.data.permissions[0].read").value(true))
            .andExpect(jsonPath("$.data.permissions[0].download").value(false));
        verify(mapper,times(2)).menus();verifyNoMoreInteractions(mapper);
    }
    @Test void deniedMenuIsNotReturnedAndAnonymousRequestsNeverReadDatabase() throws Exception {
        doThrow(ApiException.forbidden()).when(permissions).can("UI-16.read");
        mvc.perform(get("/api/navigation")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.menus").isEmpty()).andExpect(jsonPath("$.data.dashboardAllowed").value(false));
        clearInvocations(mapper);
        when(user.principal()).thenThrow(ApiException.unauthorized());
        mvc.perform(get("/api/navigation")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/me/permissions")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(mapper);
    }
}
