package com.sicost.systemmanagement;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.shared.SharedReadMapper;
import com.sicost.shared.SharedReadModels.MenuRow;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NavigationApiTest {
    SharedReadMapper mapper;
    CurrentUser user;
    PermissionService permissions;
    MockMvc mvc;
    @BeforeEach void setup() {
        mapper=mock(SharedReadMapper.class);user=mock(CurrentUser.class);permissions=mock(PermissionService.class);
        when(mapper.menus()).thenReturn(List.of(new MenuRow(792,null,"인력","UI-02","people",true),
            new MenuRow(793,null,"시스템","UI-23","admin",false)));
        mvc=MockMvcBuilders.standaloneSetup(new NavigationController(new NavigationService(mapper,permissions,user)))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @Test void navigationFiltersDeniedMenuAndReadsStoredNames()throws Exception {
        doThrow(PermissionService.forbidden()).when(permissions).can("UI-23.read");
        mvc.perform(get("/api/navigation")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].menuId").value(792)).andExpect(jsonPath("$.data[0].name").value("인력"));
        verify(user).requireBusinessAccount();verify(mapper).menus();
    }
    @Test void navigationRejectsAnonymousBeforeReadingMenuRows()throws Exception {
        when(user.requireBusinessAccount()).thenThrow(CurrentUser.unauthenticated());
        mvc.perform(get("/api/navigation")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));verifyNoInteractions(mapper);
    }
    @Test void permissionResponseUsesSameVisibleMenuTree()throws Exception {
        mvc.perform(get("/api/me/permissions")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.menus[0].screenId").value("UI-02"))
            .andExpect(jsonPath("$.data.permissions[0].inq").value(true));
        verify(permissions).can("UI-02.download");
    }
}
