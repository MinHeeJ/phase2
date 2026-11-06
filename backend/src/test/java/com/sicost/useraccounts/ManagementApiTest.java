package com.sicost.useraccounts;

import com.sicost.authentication.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.rolepermissions.RolePermissionsController;
import com.sicost.menumanagement.MenuManagementController;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.authentication.AuthenticationService.*;

class ManagementApiTest {
    ManagementMapper mapper;
    AuthenticationMapper authentication;
    PermissionService permissions;
    SecurityPermissionMapper security;
    MockMvc mvc;
    AuthenticationService resetService;
    @BeforeEach void setup() {
        mapper=mock(ManagementMapper.class);authentication=mock(AuthenticationMapper.class);permissions=mock(PermissionService.class);security=mock(SecurityPermissionMapper.class);
        var current=mock(CurrentUser.class);when(current.userId()).thenReturn(101L);when(current.name()).thenReturn("시험 관리자");when(security.recoverableAdministrators()).thenReturn(1L);
        var service=new ManagementService(mapper,permissions,current,new LastAdministratorGuard(security),authentication,new ExcelExportService());
        resetService=mock(AuthenticationService.class);
        mvc=MockMvcBuilders.standaloneSetup(new UserAccountsController(service,resetService),new RolePermissionsController(service),new MenuManagementController(service)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        when(mapper.user(anyLong())).thenAnswer(invocation -> values("id",invocation.getArgument(0),"loginId","isolated.person","name","시험 계정","status","ACTIVE"));
        when(mapper.role(anyLong())).thenAnswer(invocation -> values("id",invocation.getArgument(0),"name","사용자 정의","description","설명","dataScope","PART","useYn","Y","admin",false,"builtIn",false,"sensitiveInquiry",false,"partStandardChange",false));
    }
    @Test void usersListUsesSameFiltersAndDatabaseTotal() throws Exception {
        when(mapper.users(any())).thenReturn(List.of(values("id",23L,"loginId","isolated.person","name","시험")));when(mapper.userCount(any())).thenReturn(41L);
        mvc.perform(get("/api/admin/users").param("name","시험").param("page","1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(41)).andExpect(jsonPath("$.data.items[0].loginId").value("isolated.person"));
        var query=org.mockito.ArgumentCaptor.forClass(Map.class);verify(mapper).users(query.capture());verify(mapper).userCount(query.getValue());org.assertj.core.api.Assertions.assertThat(query.getValue().get("offset")).isEqualTo(20);
    }
    @Test void emptyRoleCreationIs400WithNoPartialUser() throws Exception {
        mvc.perform(post("/api/admin/users").contentType("application/json").content("{\"loginId\":\"isolated.person\",\"name\":\"시험\",\"email\":\"trial@example.invalid\",\"roleIds\":[],\"partIds\":[],\"projectIds\":[]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("roleIds"));verify(mapper,never()).createUser(any());verify(mapper,never()).insertAssignment(any());
    }
    @Test void disableUsesSharedDatabaseGuardAndRevokesAllTargetSessions() throws Exception {
        mvc.perform(post("/api/admin/users/23/disable")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        var order=inOrder(security,mapper,authentication);order.verify(security).lockAdministrator();order.verify(mapper).patchUser(argThat(q -> q.get("id").equals(23L) && q.get("status").equals("DISABLED")));order.verify(authentication).revoke(argThat(q -> q.get("id").equals(23L)));order.verify(security).recoverableAdministrators();
    }
    @Test void lastAdministratorCannotBeRemoved() throws Exception {
        when(security.recoverableAdministrators()).thenReturn(0L);
        mvc.perform(post("/api/admin/users/23/disable")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
    }
    @Test void roleCopyStripsAllFourPrivileges() throws Exception {
        when(mapper.createRole(any())).thenReturn(91L);
        mvc.perform(post("/api/admin/roles/23/copy").contentType("application/json").content("{\"name\":\"독립 복사\",\"description\":\"복사 시험\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(91));
        verify(mapper).createRole(argThat(q -> Boolean.FALSE.equals(q.get("admin")) && Boolean.FALSE.equals(q.get("builtIn")) && Boolean.FALSE.equals(q.get("sensitiveInquiry")) && Boolean.FALSE.equals(q.get("partStandardChange"))));verify(mapper).copyPermissions(argThat(q -> q.get("id").equals(91L) && q.get("copyRoleId").equals(23L)));
    }
    @Test void nonInquiryWriteCombinationRejectedBeforeAnyPermissionWrite() throws Exception {
        when(mapper.menus()).thenReturn(List.of(values("id",12L,"screenId","UI-12")));
        mvc.perform(put("/api/admin/roles/23/permissions").contentType("application/json").content("{\"reason\":\"입력 검증\",\"permissions\":[{\"menuId\":12,\"inq\":false,\"reg\":true,\"mdfcn\":false,\"del\":false,\"upld\":false,\"dwnld\":false,\"cnfrm\":false}]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("inq"));verify(mapper,never()).savePermission(any());
    }
    @Test void screenIdentityCannotBePatched() throws Exception {
        mvc.perform(patch("/api/admin/menus/23").contentType("application/json").content("{\"screenId\":\"UI-24\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));verify(mapper,never()).patchMenu(any());
    }
    @Test void deniedAdministratorActionNeverQueriesAccount() throws Exception {
        doThrow(ApiException.forbidden()).when(permissions).can("UI-23.read");
        mvc.perform(get("/api/admin/users/23")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));verify(mapper,never()).user(anyLong());
    }
    @Test void accessHistoryReturnsDatabaseTotalAndFilters() throws Exception {
        when(mapper.history(any())).thenReturn(List.of(values("id",29L,"action","LOGIN")));
        when(mapper.historyCount(any())).thenReturn(1L);
        mvc.perform(get("/api/admin/access-history").param("action","LOGIN"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].action").value("LOGIN"));
        verify(mapper).history(argThat(q -> "LOGIN".equals(q.get("action"))));
    }
    @Test void roleDetailAndPermissionsReadRequestedRoleOnly() throws Exception {
        when(mapper.permissions(23L)).thenReturn(List.of(values("menuId",12L,"inq",true)));
        mvc.perform(get("/api/admin/roles/23")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dataScope").value("PART"));
        mvc.perform(get("/api/admin/roles/23/permissions")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roleId").value(23))
            .andExpect(jsonPath("$.data.permissions[0].inq").value(true));
        verify(mapper).permissions(23L);verify(mapper,never()).patchRole(any());
    }
    @Test void missingRoleIs404ForDetailAndPermissions() throws Exception {
        when(mapper.role(99L)).thenReturn(null);
        mvc.perform(get("/api/admin/roles/99")).andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/api/admin/roles/99/permissions")).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").isNotEmpty());
        verify(mapper,never()).permissions(99L);
    }
    @Test void rolePatchWritesAndRevokesSessionsButInvalidScopeDoesNot() throws Exception {
        mvc.perform(patch("/api/admin/roles/23").contentType("application/json").content("{\"description\":\"변경 사유\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(23));
        verify(mapper).patchRole(argThat(q -> "변경 사유".equals(q.get("description"))));verify(mapper).revokeAll();
        clearInvocations(mapper);
        mvc.perform(patch("/api/admin/roles/23").contentType("application/json").content("{\"dataScope\":\"INVALID\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("dataScope"));
        verify(mapper,never()).patchRole(any());verify(mapper,never()).revokeAll();
    }
    @Test void userPatchAndAssignmentWritesInvalidateTargetSessions() throws Exception {
        mvc.perform(patch("/api/admin/users/23").contentType("application/json").content("{\"name\":\"변경 이름\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(23));
        verify(mapper).patchUser(argThat(q -> "변경 이름".equals(q.get("name"))));
        mvc.perform(put("/api/admin/users/23/assignments").contentType("application/json").content("{\"basePartId\":4,\"developerId\":null,\"partIds\":[4],\"projectIds\":[7]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.loginId").value("isolated.person"));
        verify(mapper).insertAssignment(argThat(q -> "projects".equals(q.get("kind")) && Long.valueOf(7).equals(q.get("value"))));
        verify(authentication,times(2)).revoke(argThat(q -> Long.valueOf(23).equals(q.get("id"))));
    }
    @Test void roleAssignmentReplacesRolesAndInvalidatesTargetOnly() throws Exception {
        mvc.perform(put("/api/admin/users/23/roles").contentType("application/json").content("{\"roleIds\":[9]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(23));
        verify(mapper).deleteAssignments(argThat(q -> "roles".equals(q.get("kind"))));
        verify(mapper).insertAssignment(argThat(q -> "roles".equals(q.get("kind")) && Long.valueOf(9).equals(q.get("value"))));
        verify(authentication).revoke(argThat(q -> Long.valueOf(23).equals(q.get("id"))));
    }
    @Test void invalidAccountWritesDoNotReachPersistence() throws Exception {
        mvc.perform(patch("/api/admin/users/23").contentType("application/json").content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("name"));
        mvc.perform(put("/api/admin/users/23/roles").contentType("application/json").content("{\"roleIds\":[]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("roleIds"));
        mvc.perform(put("/api/admin/users/23/assignments").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        verify(mapper,never()).patchUser(any());verify(mapper,never()).deleteAssignments(any());verifyNoInteractions(authentication);
    }
    @Test void unlockRequiresLockedStateAndRecordsAudit() throws Exception {
        when(mapper.user(23L)).thenReturn(values("id",23L,"status","LOCKED"));
        mvc.perform(post("/api/admin/users/23/unlock")).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(23));
        verify(mapper).patchUser(argThat(q -> "ACTIVE".equals(q.get("status")) && Boolean.TRUE.equals(q.get("unlock"))));
        verify(authentication).audit(argThat(q -> "UNLOCK".equals(q.get("action"))));
        clearInvocations(mapper);
        when(mapper.user(23L)).thenReturn(values("id",23L,"status","ACTIVE"));
        mvc.perform(post("/api/admin/users/23/unlock")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        verify(mapper,never()).patchUser(any());
    }
    @Test void passwordResetChecksPermissionAndDelegatesExactAccount() throws Exception {
        mvc.perform(post("/api/admin/users/23/password-reset")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(permissions).can("UI-23.update");verify(resetService).requestReset(eq(23L),any(),any());
        doThrow(ApiException.notFound("계정이 없습니다.")).when(resetService).requestReset(eq(99L),any(),any());
        mvc.perform(post("/api/admin/users/99/password-reset")).andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
    }
    @Test void menusListCreateAndReorderUseDatabaseRows() throws Exception {
        var existing=values("id",12L,"name","업무","screenId","UI-12","useYn","Y","mobileVisible",true,"sortOrder",0);
        var created=values("id",13L,"name","새 메뉴","screenId","UI-13","useYn","Y","mobileVisible",true,"sortOrder",1);
        when(mapper.menus()).thenReturn(List.of(existing));
        mvc.perform(get("/api/admin/menus")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].screenId").value("UI-12"));
        when(mapper.createMenu(any())).thenAnswer(i -> {when(mapper.menus()).thenReturn(List.of(existing,created));return 13L;});
        mvc.perform(post("/api/admin/menus").contentType("application/json").content("{\"name\":\"새 메뉴\",\"screenId\":\"UI-13\",\"useYn\":\"Y\",\"mobileVisible\":true,\"sortOrder\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(13));
        verify(mapper).createMenu(argThat(q -> "UI-13".equals(q.get("screenId"))));
        mvc.perform(put("/api/admin/menus/reorder").contentType("application/json").content("{\"items\":[{\"id\":12,\"sortOrder\":1},{\"id\":13,\"sortOrder\":0}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper,times(2)).patchMenu(any());verify(mapper).revokeAll();
    }
    @Test void invalidMenuCreateAndIncompleteReorderDoNotWrite() throws Exception {
        when(mapper.menus()).thenReturn(List.of(values("id",12L)));
        mvc.perform(post("/api/admin/menus").contentType("application/json").content("{\"screenId\":\"UI-99\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("screenId"));
        mvc.perform(put("/api/admin/menus/reorder").contentType("application/json").content("{\"items\":[]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items"));
        verify(mapper,never()).createMenu(any());verify(mapper,never()).patchMenu(any());
    }
    @Test void notificationChannelReadAndWriteUseStoredBoolean() throws Exception {
        when(mapper.channel(anyString())).thenReturn(false);
        mvc.perform(get("/api/admin/notification-channels")).andExpect(status().isOk()).andExpect(jsonPath("$.data.businessEmailEnabled").value(false));
        doAnswer(i -> {when(mapper.channel(anyString())).thenReturn(true);return null;}).when(mapper).saveChannel(any());
        mvc.perform(put("/api/admin/notification-channels").contentType("application/json").content("{\"businessEmailEnabled\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.businessEmailEnabled").value(true));
        verify(mapper).saveChannel(argThat(q -> Boolean.TRUE.equals(q.get("businessEmailEnabled"))));
        clearInvocations(mapper);
        mvc.perform(put("/api/admin/notification-channels").contentType("application/json").content("{\"businessEmailEnabled\":\"true\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("businessEmailEnabled"));
        verify(mapper,never()).saveChannel(any());
    }
    @Test void usersExportIsAnActualWorkbookAndAuditsDownload() throws Exception {
        when(mapper.users(any())).thenReturn(List.of(values("loginId","isolated.person","name","시험","status","ACTIVE")));
        byte[] bytes=mvc.perform(get("/api/admin/users/export").param("status","ACTIVE"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition","attachment; filename=\"users.xlsx\""))
            .andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            org.assertj.core.api.Assertions.assertThat(book.getSheetAt(0).getRow(4).getCell(0).getStringCellValue()).isEqualTo("isolated.person");
        }
        verify(authentication).audit(argThat(q -> "DOWNLOAD".equals(q.get("action")) && "USERS".equals(q.get("target"))));
    }
    @Test void accessHistoryExportsOnlySafeFields() throws Exception {
        when(mapper.history(any())).thenReturn(List.of(values("id",29L,"userId",101L,"action","LOGIN","ip","127.0.0.1")));
        mvc.perform(get("/api/admin/access-history/export").param("action","LOGIN")).andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=\"access-history.xlsx\""));
        verify(permissions).can("UI-23.download");verify(mapper).history(argThat(q -> q.get("action").equals("LOGIN") && !q.containsKey("size")));
    }
}
