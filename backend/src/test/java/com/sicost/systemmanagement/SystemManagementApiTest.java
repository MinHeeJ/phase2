package com.sicost.systemmanagement;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.authentication.AuthenticationService;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SystemManagementApiTest {
    SystemManagementMapper mapper;PermissionService permissions;LastAdministratorGuard administrators;MockMvc mvc;
    Map<String,Object> role;
    AuthenticationService authentication;
    @BeforeEach void setup() {
        mapper=mock(SystemManagementMapper.class);permissions=mock(PermissionService.class);administrators=mock(LastAdministratorGuard.class);
        CurrentUser user=mock(CurrentUser.class);when(user.userId()).thenReturn(417L);when(user.name()).thenReturn("검증 관리자");
        authentication=mock(AuthenticationService.class);
        var service=new SystemManagementService(mapper,permissions,user,administrators,new BCryptPasswordEncoder(4),authentication);
        mvc=MockMvcBuilders.standaloneSetup(new SystemManagementController(service,new ExcelExportService(),user,Clock.system(ZoneId.of("Asia/Seoul"))))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        role=new HashMap<>(Map.of("id",837L,"name","검증 역할","dataScope","PART","useYn","Y","sensitiveView",false,"partStandardChange",false,"admin",false,"builtIn",false));
        when(mapper.role(837L)).thenAnswer(i->new HashMap<>(role));
    }
    @Test void roleDetailReadsDatabaseAndMissingRoleIs404()throws Exception {
        mvc.perform(get("/api/admin/roles/837")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(837)).andExpect(jsonPath("$.data.dataScope").value("PART"));
        mvc.perform(get("/api/admin/roles/999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        verify(mapper,never()).updateRole(any());
    }
    @Test void permissionsReadRequiresExistingRoleAndPreservesRows()throws Exception {
        when(mapper.permissions(837L)).thenReturn(List.of(Map.of("menuId",792L,"inq",true)));
        mvc.perform(get("/api/admin/roles/837/permissions")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].menuId").value(792)).andExpect(jsonPath("$.data[0].inq").value(true));
        mvc.perform(get("/api/admin/roles/999/permissions")).andExpect(status().isNotFound());
        verify(mapper,never()).savePermission(any());
    }
    @Test void createRolePersistsActorAndRejectsInvalidScope()throws Exception {
        when(mapper.createRole(any())).thenReturn(837L);
        mvc.perform(post("/api/admin/roles").contentType("application/json").content("{\"name\":\"검증 역할\",\"dataScope\":\"PART\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(837));
        verify(mapper).createRole(argThat(w->Long.valueOf(417).equals(w.get("actor")) && Boolean.FALSE.equals(w.get("admin"))));
        verify(mapper).audit(argThat(w->"PERM_CHANGE".equals(w.get("type"))));
        mvc.perform(post("/api/admin/roles").contentType("application/json").content("{\"name\":\"검증\",\"dataScope\":\"INVALID\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("dataScope"));
        verify(mapper,times(1)).createRole(any());
    }
    @Test void menuListUsesStoredRowsAndChecksReadPermission()throws Exception {
        when(mapper.menus()).thenReturn(List.of(Map.of("id",792L,"name","검증 메뉴")));
        mvc.perform(get("/api/admin/menus")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("검증 메뉴"));
        verify(permissions).can("UI-25.read");verify(mapper,never()).updateMenu(any());
    }
    @Test void menuCreationReturnsPersistedMenuAndRejectsUnknownScreen()throws Exception {
        when(mapper.menus()).thenReturn(List.of(),List.of(Map.of("id",792L,"name","검증 메뉴")));
        when(mapper.createMenu(any())).thenReturn(792L);
        mvc.perform(post("/api/admin/menus").contentType("application/json").content("{\"name\":\"검증 메뉴\",\"screenId\":\"UI-02\",\"sortOrder\":1,\"useYn\":\"Y\",\"mobileVisible\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(792));
        verify(mapper).createMenu(argThat(w->Long.valueOf(417).equals(w.get("actor"))));
        mvc.perform(post("/api/admin/menus").contentType("application/json").content("{\"name\":\"검증 메뉴\",\"screenId\":\"UI-99\",\"sortOrder\":1,\"useYn\":\"Y\",\"mobileVisible\":true}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("screenId"));
        verify(mapper,times(1)).createMenu(any());
    }
    @Test void patchMenuWritesOnlyValidTreeAndMissingMenuIs404()throws Exception {
        when(mapper.menus()).thenReturn(List.of(Map.of("id",792L,"name","기존","sortOrder",1,"useYn","Y","mobileVisible",true)));
        mvc.perform(patch("/api/admin/menus/792").contentType("application/json").content("{\"name\":\"변경\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper).updateMenu(argThat(w->"변경".equals(w.get("name"))));
        mvc.perform(patch("/api/admin/menus/999").contentType("application/json").content("{\"name\":\"변경\"}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        verify(mapper,times(1)).updateMenu(any());
    }
    @Test void reorderRejectsIncompleteInventoryBeforeAnyWrite()throws Exception {
        when(mapper.menus()).thenReturn(List.of(Map.of("id",792L,"name","기존","sortOrder",1,"useYn","Y","mobileVisible",true)));
        mvc.perform(put("/api/admin/menus/reorder").contentType("application/json").content("{\"items\":[]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items"));
        verify(mapper,never()).updateMenu(any());
        mvc.perform(put("/api/admin/menus/reorder").contentType("application/json").content("{\"items\":[{\"menuId\":792,\"sortOrder\":2}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper).updateMenu(argThat(w->Integer.valueOf(2).equals(w.get("sortOrder"))));
    }
    @Test void userCreationWritesRelationsAndRequestsInitialPasswordMail()throws Exception {
        when(mapper.partExists(792L)).thenReturn(true);when(mapper.createUser(any())).thenReturn(731L);
        when(mapper.user(731L)).thenAnswer(i->new HashMap<>(Map.of("id",731L,"name","검증 사용자")));
        mvc.perform(post("/api/admin/users").contentType("application/json").content("{\"name\":\"검증 사용자\",\"loginId\":\"contract.user\",\"email\":\"contract@example.invalid\",\"basePartId\":792,\"roleIds\":[837],\"partIds\":[],\"projectIds\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(731)).andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        verify(mapper).assignRoles(argThat(w->List.of(837L).equals(w.get("ids"))));verify(authentication).sendReset(731L);
        mvc.perform(post("/api/admin/users").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("name"));
        verify(mapper,times(1)).createUser(any());
    }
    @Test void disablePersistsDisabledStatusAndProtectsRecoveryAdministrator()throws Exception {
        when(mapper.user(731L)).thenAnswer(i->new HashMap<>(Map.of("id",731L,"name","검증 사용자","email","contract@example.invalid","status","ACTIVE")));
        mvc.perform(post("/api/admin/users/731/disable")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper).updateUser(argThat(w->"DISABLED".equals(w.get("status"))));verify(administrators).requireRecovery();
        mvc.perform(post("/api/admin/users/999/disable")).andExpect(status().isNotFound());verify(mapper,times(1)).updateUser(any());
    }
    @Test void unlockAuditsExistingAccountButNotMissingOne()throws Exception {
        when(mapper.user(731L)).thenReturn(Map.of("id",731L));
        mvc.perform(post("/api/admin/users/731/unlock")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper).unlock(731L);verify(mapper).audit(argThat(w->"UNLOCK".equals(w.get("type"))));
        mvc.perform(post("/api/admin/users/999/unlock")).andExpect(status().isNotFound());verify(mapper,never()).unlock(999L);
    }
    @Test void passwordResetDelegatesOnlyForExistingAccount()throws Exception {
        when(mapper.user(731L)).thenReturn(Map.of("id",731L));
        mvc.perform(post("/api/admin/users/731/password-reset")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(authentication).sendReset(731L);
        mvc.perform(post("/api/admin/users/999/password-reset")).andExpect(status().isNotFound());verify(authentication,never()).sendReset(999L);
    }
    @Test void roleAssignmentReplacesRolesButRejectsEmptySelection()throws Exception {
        when(mapper.user(731L)).thenReturn(Map.of("id",731L));
        mvc.perform(put("/api/admin/users/731/roles").contentType("application/json").content("{\"roleIds\":[837]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper).clearRoles(731L);verify(mapper).assignRoles(argThat(w->List.of(837L).equals(w.get("ids"))));
        mvc.perform(put("/api/admin/users/731/roles").contentType("application/json").content("{\"roleIds\":[]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("roleIds"));verify(mapper,times(1)).clearRoles(anyLong());
    }
    @Test void assignmentScopeChecksAllReferencesBeforeReplacement()throws Exception {
        when(mapper.user(731L)).thenReturn(Map.of("id",731L));when(mapper.partExists(792L)).thenReturn(true);
        mvc.perform(put("/api/admin/users/731/assignments").contentType("application/json").content("{\"partIds\":[792],\"projectIds\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper).assignParts(argThat(w->List.of(792L).equals(w.get("ids"))));
        mvc.perform(put("/api/admin/users/731/assignments").contentType("application/json").content("{\"partIds\":[999],\"projectIds\":[]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("partIds"));verify(mapper,times(1)).clearParts(731L);
    }
    @Test void accessHistoryExportIsRealWorkbookAndDoesNotWriteBusinessData()throws Exception {
        when(mapper.history(any())).thenReturn(List.of(Map.of("userId",417L,"type","LOGIN","result","SUCCESS")));
        byte[] bytes=mvc.perform(get("/api/admin/access-history/export").param("userId","417"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition","attachment; filename=\"history.xlsx\""))
            .andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            org.assertj.core.api.Assertions.assertThat(workbook.getSheetAt(0).getRow(4).getCell(3).getStringCellValue()).isEqualTo("LOGIN");
        }
        verify(permissions).can("UI-23.download");verify(mapper,never()).updateUser(any());
    }
    @Test void roleListIsDatabaseBackedAndRequiresAdminMenuRead()throws Exception {
        when(mapper.roles()).thenReturn(List.of(role));
        mvc.perform(get("/api/admin/roles")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(837));verify(permissions).can("UI-24.read");
    }
    @Test void deniedUserListDoesNotTouchDatabase()throws Exception {
        doThrow(PermissionService.forbidden()).when(permissions).can("UI-23.read");
        mvc.perform(get("/api/admin/users")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));verifyNoInteractions(mapper);
    }
    @Test void missingUserReturns404()throws Exception { when(mapper.user(731L)).thenReturn(null);mvc.perform(get("/api/admin/users/731")).andExpect(status().isNotFound()); }
    @Test void readonlyUserIdentityCannotBePatched()throws Exception {
        mvc.perform(patch("/api/admin/users/731").contentType("application/json").content("{\"loginId\":\"changed\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("loginId"));verify(mapper,never()).updateUser(any());
    }
    @Test void roleCopyDropsAllPrivilegesButKeepsOriginalScope()throws Exception {
        role.put("admin",true);role.put("builtIn",true);role.put("sensitiveView",true);role.put("partStandardChange",true);
        when(mapper.createRole(any())).thenReturn(938L);when(mapper.role(938L)).thenReturn(Map.of("id",938L,"admin",false,"builtIn",false,"sensitiveView",false,"partStandardChange",false));
        mvc.perform(post("/api/admin/roles/837/copy").contentType("application/json").content("{\"name\":\"새 검증 역할\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.admin").value(false)).andExpect(jsonPath("$.data.sensitiveView").value(false));
        verify(mapper).createRole(argThat(w->Boolean.FALSE.equals(w.get("admin")) && Boolean.FALSE.equals(w.get("sensitiveView")) && Boolean.FALSE.equals(w.get("partStandardChange")) && "PART".equals(w.get("dataScope"))));
        verify(mapper).copyPermissions(Map.of("id",938L,"sourceId",837L));verify(mapper,never()).updateRole(any());
    }
    @Test void builtInAdministratorAttributeIsImmutable()throws Exception {
        role.put("admin",true);role.put("builtIn",true);
        mvc.perform(patch("/api/admin/roles/837").contentType("application/json").content("{\"admin\":false}"))
            .andExpect(status().isBadRequest());verify(mapper,never()).updateRole(any());
    }
    @Test void invalidPermissionCombinationIsRejectedBeforeWrites()throws Exception {
        when(mapper.menus()).thenReturn(List.of(Map.of("id",792L,"screenId","UI-02")));
        mvc.perform(put("/api/admin/roles/837/permissions").contentType("application/json").content("""
            {"reason":"검증","permissions":[{"menuId":792,"inq":false,"reg":true,"mdfcn":false,"del":false,"upld":false,"dwnld":false,"cnfrm":false}]}
            """))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("inq"));verify(mapper,never()).savePermission(any());
    }
    @Test void lastAdminFailureUses409Envelope()throws Exception {
        role.put("admin",true);role.put("builtIn",true);doThrow(ApiException.conflict("LAST_ADMIN","관리자를 유지하세요.",null)).when(administrators).requireRecovery();
        mvc.perform(patch("/api/admin/roles/837").contentType("application/json").content("{\"useYn\":\"N\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
    }
    @Test void unknownQueryIs400AndNoSqlIsExecuted()throws Exception {
        mvc.perform(get("/api/admin/users").param("sql","select secret"))
            .andExpect(status().isBadRequest());verifyNoInteractions(mapper);
    }
    @Test void auditReadNeverExposesHistoricalDetailBody()throws Exception {
        when(mapper.history(any())).thenReturn(List.of(Map.of("id",491L,"type","LOGIN","result","UNKNOWN")));when(mapper.historyCount(any())).thenReturn(1L);
        mvc.perform(get("/api/admin/access-history").param("userId","417")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].result").value("UNKNOWN")).andExpect(jsonPath("$.data.items[0].detail").doesNotExist());
    }
    @Test void userPartAndRoleFiltersReachBothRowsAndTotalWithTypedIdentifiers()throws Exception {
        when(mapper.users(any())).thenReturn(List.of(new HashMap<>(Map.of("id",731L,"name","검증 사용자"))));
        when(mapper.userCount(any())).thenReturn(51L);
        mvc.perform(get("/api/admin/users").param("partId","792").param("roleId","837").param("page","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(51))
            .andExpect(jsonPath("$.data.page").value(2)).andExpect(jsonPath("$.data.items[0].id").value(731));
        var query=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(mapper).users(query.capture());
        org.assertj.core.api.Assertions.assertThat(query.getValue()).containsEntry("partId",792L).containsEntry("roleId",837L).containsEntry("offset",50L);
        verify(mapper).userCount(query.getValue());verify(permissions).can("UI-23.read");
    }
    @Test void exportKeepsSubmittedFiltersButNeverUsesListPagination()throws Exception {
        when(mapper.users(any())).thenReturn(List.of(Map.of("loginId","filter-check","name","검증 사용자","status","ACTIVE")));
        mvc.perform(get("/api/admin/users/export").param("partId","792").param("roleId","837").param("page","2"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition","attachment; filename=\"users.xlsx\""));
        verify(mapper).users(argThat(q->Long.valueOf(792).equals(q.get("partId")) && Long.valueOf(837).equals(q.get("roleId")) && !q.containsKey("limit") && !q.containsKey("offset")));
        verify(permissions).can("UI-23.download");verify(mapper,never()).userCount(any());
    }
}
