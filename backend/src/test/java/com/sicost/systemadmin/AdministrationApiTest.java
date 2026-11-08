package com.sicost.systemadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.authentication.AuthenticationService;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.common.security.SecurityModels.*;
import jakarta.servlet.http.Cookie;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(AdministrationController.class)
@Import({AdministrationService.class,AuthorizationService.class,PermissionService.class,CurrentUser.class,SecurityConfiguration.class,RequestIdFilter.class,ApiExceptionHandler.class,ExcelExportService.class,AdministrationApiTest.Time.class})
class AdministrationApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"),SessionService.SEOUL);} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AdministrationMapper mapper;
    @MockBean SecurityStateMapper security;
    @MockBean SessionService sessions;
    @MockBean AuthenticationService authentication;
    Cookie cookie;
    Long actor;
    @BeforeEach void setup() {
        actor=Math.abs(UUID.randomUUID().getMostSignificantBits()%1000000000L)+1;
        String raw=AuthenticationProof.randomId();cookie=new Cookie("JSESSIONID",raw);
        when(sessions.authenticate(raw)).thenReturn(new SecurityPrincipal(actor,"관리자",List.of(1L),AuthenticationProof.digest(raw),false));
        when(sessions.expiresAt(any())).thenReturn(Instant.parse("2026-11-08T01:30:00Z"));
        when(security.account(actor)).thenReturn(new Account(actor,"관리자",null,"ACTIVE",null,false));
        when(security.permissions(actor)).thenReturn(List.of(grant("UI-23",true),grant("UI-24",true),grant("UI-25",true)));
        when(security.parts(actor)).thenReturn(List.of());when(security.projects(actor)).thenReturn(List.of());
        when(mapper.readyAdministrators()).thenReturn(1L);
        when(mapper.user(anyLong())).thenReturn(null);
        when(mapper.role(anyLong())).thenReturn(null);
    }
    Permission grant(String screen,boolean admin) { return new Permission(1L,"시스템 관리자",DataScope.ALL,admin,true,true,screen,"YYYYYYY",false,false); }
    Map<String,Object> user(Long id) { return new HashMap<>(Map.of("userId",id,"loginId","account-"+UUID.randomUUID(),"name","시험 사용자","status","ACTIVE")); }
    Map<String,Object> role(Long id,boolean admin) { return new HashMap<>(Map.of("roleId",id,"name","역할-"+UUID.randomUUID(),"dataScope","PART","useYn","Y","admin",admin,"builtin",false,"sensitiveInquiry",false,"partStandardChange",false)); }
    Map<String,Object> menu(Long id,String screen) {var row=new HashMap<String,Object>();row.put("menuId",id);row.put("name","메뉴");row.put("screenId",screen);row.put("parentId",null);row.put("sortOrder",1L);row.put("useYn","Y");row.put("mobileVisible",true);return row;}
    @ParameterizedTest @ValueSource(strings={"/api/admin/users","/api/admin/roles","/api/admin/menus","/api/admin/access-history","/api/admin/notification-channels"})
    void anonymousIs401(String path)throws Exception {mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));verifyNoInteractions(mapper);}
    @Test void actionWithoutAdminIsForbidden()throws Exception {
        when(security.permissions(actor)).thenReturn(List.of(grant("UI-23",false)));
        mvc.perform(get("/api/admin/users").cookie(cookie)).andExpect(status().isForbidden());verifyNoInteractions(mapper);
    }
    @Test void listUsesSameFilterForRowsAndTotalAndOmitsCredentials()throws Exception {
        var row=user(actor);when(mapper.users(any())).thenReturn(List.of(row));when(mapper.userCount(any())).thenReturn(1L);
        mvc.perform(get("/api/admin/users").param("name","시험").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].roleIds").isArray()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());
        verify(mapper).userCount(argThat(q->"시험".equals(q.get("name"))));
    }
    @Test void createPersistsAssignmentsAndReservesResetWithoutReturningProof()throws Exception {
        Long id=actor+1;when(mapper.validReference(any(),any())).thenReturn(true);when(mapper.role(8L)).thenReturn(role(8L,false));when(mapper.insertUser(any())).thenReturn(id);when(mapper.user(id)).thenReturn(user(id));
        var body=Map.of("loginId","new-"+UUID.randomUUID(),"name","신규 사용자","email","test@example.invalid","roleIds",List.of(8),"partIds",List.of(12),"projectIds",List.of(15));
        mvc.perform(post("/api/admin/users").cookie(cookie).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(id)).andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andExpect(jsonPath("$.data.token").doesNotExist());
        verify(mapper).addRole(id,8L);verify(mapper).addPart(id,12L);verify(mapper).addProject(id,15L);verify(authentication).reserveReset(id);
        verify(mapper).insertUser(argThat(w->actor.equals(w.get("actor"))&&((String)w.get("passwordHash")).startsWith("$2")));
    }
    @Test void emptyRoleSetCannotPartiallyCreateUser()throws Exception {
        mvc.perform(post("/api/admin/users").cookie(cookie).with(csrf()).contentType("application/json").content("{\"loginId\":\"valid\",\"name\":\"시험\",\"email\":\"test@example.invalid\",\"roleIds\":[],\"partIds\":[],\"projectIds\":[]}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("roleIds"));verify(mapper,never()).insertUser(any());
    }
    @Test void patchExcludesImmutableLoginAndSeparateAssignments()throws Exception {
        when(mapper.user(actor)).thenReturn(user(actor));
        mvc.perform(patch("/api/admin/users/{id}",actor).cookie(cookie).with(csrf()).contentType("application/json").content("{\"loginId\":\"changed\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("loginId"));verify(mapper,never()).updateUser(any());
    }
    @Test void lastAdministratorFutureRetirementIs409()throws Exception {
        when(mapper.user(actor)).thenReturn(user(actor));when(mapper.readyAdministrators()).thenReturn(0L);
        mvc.perform(patch("/api/admin/users/"+actor).cookie(cookie).with(csrf()).contentType("application/json").content("{\"resignationDate\":\"2099-01-01\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));verify(mapper).lockAdministrators();verify(mapper,never()).revokeUser(any());
    }
    @Test void assignmentInvalidTargetFailsBeforeAnyReplacement()throws Exception {
        when(mapper.user(actor)).thenReturn(user(actor));when(mapper.validReference("part",13L)).thenReturn(false);
        mvc.perform(put("/api/admin/users/{id}/assignments",actor).cookie(cookie).with(csrf()).contentType("application/json").content("{\"partIds\":[13],\"projectIds\":[]}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));verify(mapper,never()).clearParts(any());verify(mapper,never()).clearProjects(any());
    }
    @Test void unlockAndResetRequireExplicitCommands()throws Exception {
        when(mapper.user(actor)).thenReturn(user(actor));
        mvc.perform(post("/api/admin/users/{id}/unlock",actor).cookie(cookie).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(actor));verify(mapper).unlock(actor);
        mvc.perform(post("/api/admin/users/{id}/password-reset",actor).cookie(cookie).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));verify(authentication).reserveReset(actor);
    }
    @Test void userRolesReplaceOnlyAfterAllReferencesValidate()throws Exception {
        when(mapper.user(actor)).thenReturn(user(actor));when(mapper.validReference("role",8L)).thenReturn(true);when(mapper.role(8L)).thenReturn(role(8L,false));
        when(mapper.userRoles(actor)).thenReturn(List.of(8L));
        mvc.perform(put("/api/admin/users/{id}/roles",actor).cookie(cookie).with(csrf()).contentType("application/json").content("{\"roleIds\":[8]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.roleIds[0]").value(8));
        verify(mapper).clearRoles(actor);verify(mapper).addRole(actor,8L);verify(mapper).revokeUser(actor);verify(mapper).audit(any());
    }
    @Test void disableUserPersistsStatusRevokesSessionsAndAudits()throws Exception {
        var row=user(actor);when(mapper.user(actor)).thenReturn(row);
        doAnswer(i->{row.put("status","DISABLED");return null;}).when(mapper).updateUser(any());
        mvc.perform(post("/api/admin/users/{id}/disable",actor).cookie(cookie).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISABLED"));
        verify(mapper).updateUser(argThat(w->"DISABLED".equals(w.get("status"))));verify(mapper).revokeUser(actor);verify(mapper).audit(any());
    }
    @Test void roleDetailAndPermissionsAreReadOnlyAndUnknownRoleIs404()throws Exception {
        when(mapper.role(8L)).thenReturn(role(8L,false));when(mapper.permissions(8L)).thenReturn(List.of(Map.of("menuId",4L,"inq","Y")));
        mvc.perform(get("/api/admin/roles/8").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.roleId").value(8));
        mvc.perform(get("/api/admin/roles/8/permissions").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].inq").value("Y"));
        mvc.perform(get("/api/admin/roles/999").cookie(cookie)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper,never()).updateRole(any());verify(mapper,never()).savePermission(any());
    }
    @Test void createRolePersistsValidatedScopeAndAudit()throws Exception {
        when(mapper.insertRole(any())).thenReturn(8L);when(mapper.role(8L)).thenReturn(role(8L,false));
        mvc.perform(post("/api/admin/roles").cookie(cookie).with(csrf()).contentType("application/json").content("{\"name\":\"조회 역할\",\"dataScope\":\"PART\",\"useYn\":\"Y\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.roleId").value(8)).andExpect(jsonPath("$.data.admin").value(false));
        verify(mapper).insertRole(argThat(w->"PART".equals(w.get("dataScope"))&&actor.equals(w.get("actor"))));verify(mapper).audit(any());
    }
    @Test void invalidRoleCannotWrite()throws Exception {
        mvc.perform(post("/api/admin/roles").cookie(cookie).with(csrf()).contentType("application/json").content("{\"name\":\"\",\"dataScope\":\"PART\",\"useYn\":\"Y\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("name"));verify(mapper,never()).insertRole(any());
    }
    @Test void patchRolePreservesBuiltinIdentity()throws Exception {
        var row=role(8L,true);row.put("builtin",true);when(mapper.role(8L)).thenReturn(row);
        mvc.perform(patch("/api/admin/roles/8").cookie(cookie).with(csrf()).contentType("application/json").content("{\"name\":\"교체\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("name"));verify(mapper,never()).updateRole(any());
    }
    @Test void channelsReadReturnsStoredBusinessToggleWithoutWrites()throws Exception {
        when(mapper.mailEnabled()).thenReturn(true);
        mvc.perform(get("/api/admin/notification-channels").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.businessEmailEnabled").value(true));
        verify(mapper).mailEnabled();verify(mapper,never()).saveMailChannel(any());
    }
    @Test void roleCopyResetsFourPrivilegesAndCopiesOnlyActions()throws Exception {
        when(mapper.role(8L)).thenReturn(role(8L,true));when(mapper.insertRole(any())).thenReturn(9L);when(mapper.role(9L)).thenReturn(role(9L,false));
        mvc.perform(post("/api/admin/roles/8/copy").cookie(cookie).with(csrf()).contentType("application/json").content("{\"name\":\"복사 역할\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.admin").value(false));
        verify(mapper).insertRole(argThat(w->Boolean.FALSE.equals(w.get("admin"))&&Boolean.FALSE.equals(w.get("sensitiveInquiry"))&&Boolean.FALSE.equals(w.get("partStandardChange"))));verify(mapper).copyPermissions(8L,9L);
    }
    @Test void viewNWithWriteYDoesNotWriteAnyPermission()throws Exception {
        when(mapper.role(8L)).thenReturn(role(8L,true));when(mapper.menus()).thenReturn(List.of(menu(4L,"UI-02")));
        mvc.perform(put("/api/admin/roles/8/permissions").cookie(cookie).with(csrf()).contentType("application/json").content("{\"reason\":\"시험 변경\",\"permissions\":[{\"menuId\":4,\"inq\":\"N\",\"reg\":\"Y\",\"mdfcn\":\"N\",\"del\":\"N\",\"upld\":\"N\",\"dwnld\":\"N\",\"cnfrm\":\"N\"}]}")).andExpect(status().isBadRequest());verify(mapper,never()).savePermission(any());
    }
    @Test void cyclicMenuOrderDoesNotPartiallyWrite()throws Exception {
        when(mapper.menus()).thenReturn(List.of(menu(4L,null),menu(5L,null)));
        mvc.perform(put("/api/admin/menus/reorder").cookie(cookie).with(csrf()).contentType("application/json").content("{\"items\":[{\"menuId\":4,\"parentId\":5,\"sortOrder\":1},{\"menuId\":5,\"parentId\":4,\"sortOrder\":2}]}")).andExpect(status().isBadRequest());verify(mapper,never()).updateMenu(any());
    }
    @Test void fixedScreenIdIsNotPatchable()throws Exception {
        when(mapper.menus()).thenReturn(List.of(menu(4L,"UI-23")));
        mvc.perform(patch("/api/admin/menus/4").cookie(cookie).with(csrf()).contentType("application/json").content("{\"screenId\":\"UI-02\"}")).andExpect(status().isBadRequest());verify(mapper,never()).updateMenu(any());
    }
    @Test void channelsPersistOnlyBusinessToggle()throws Exception {
        when(mapper.mailEnabled()).thenReturn(true);
        mvc.perform(put("/api/admin/notification-channels").cookie(cookie).with(csrf()).contentType("application/json").content("{\"businessEmailEnabled\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.businessEmailEnabled").value(true));verify(mapper).saveMailChannel(argThat(w->Boolean.TRUE.equals(w.get("businessEmailEnabled"))));
    }
    @Test void auditReadDoesNotExposeRawDetailAndDownloadHasProvenance()throws Exception {
        when(mapper.history(any())).thenReturn(List.of(Map.of("createdAt","2026-11-08T10:00:00","userId",actor,"type","CONTACT_VIEW")));when(mapper.historyCount(any())).thenReturn(1L);
        mvc.perform(get("/api/admin/access-history").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].type").value("CONTACT_VIEW")).andExpect(jsonPath("$.data.items[0].dtl_desc").doesNotExist());
        mvc.perform(get("/api/admin/access-history/export").cookie(cookie)).andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("access-history.xlsx")));
    }
    @Test void csrfMissingCannotMutate()throws Exception {mvc.perform(post("/api/admin/users").cookie(cookie).contentType("application/json").content("{}")).andExpect(status().isForbidden());verifyNoInteractions(mapper);}
    @ParameterizedTest @ValueSource(strings={"/api/admin/users/export","/api/admin/access-history/export"})
    void inquiryAloneCannotExportEvenForAdministrator(String path)throws Exception {
        when(security.permissions(actor)).thenReturn(List.of(new Permission(1L,"시스템 관리자",DataScope.ALL,true,true,true,"UI-23","YNNNNNN",false,false)));
        mvc.perform(get(path).cookie(cookie)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(mapper,never()).users(any());verify(mapper,never()).history(any());verify(mapper,never()).audit(any());
    }
    @Test void auditExportKeepsAppliedFiltersButNotPaginationAndHasOnlySafeColumns()throws Exception {
        when(mapper.history(any())).thenReturn(List.of(Map.of("createdAt","2026-11-08T10:00:00","userId",actor,"name","시험 사용자","type","DOWNLOAD","result","SUCCESS","screenId","UI-23","target","users.xlsx","ipAddress","127.0.0.1")));
        when(mapper.historyCount(any())).thenReturn(1L);
        mvc.perform(get("/api/admin/access-history").cookie(cookie).param("userId",actor.toString()).param("from","2026-11-01").param("to","2026-11-08").param("type","DOWNLOAD").param("result","SUCCESS").param("screenId","UI-23").param("page","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        byte[] bytes=mvc.perform(get("/api/admin/access-history/export").cookie(cookie).param("userId",actor.toString()).param("from","2026-11-01").param("to","2026-11-08").param("type","DOWNLOAD").param("result","SUCCESS").param("screenId","UI-23").param("page","2"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn().getResponse().getContentAsByteArray();
        var queries=org.mockito.ArgumentCaptor.forClass(Map.class);verify(mapper,times(2)).history(queries.capture());
        var list=new HashMap<>(queries.getAllValues().get(0));var exported=new HashMap<>(queries.getAllValues().get(1));
        org.assertj.core.api.Assertions.assertThat(list.remove("size")).isEqualTo(20);
        org.assertj.core.api.Assertions.assertThat(exported).isEqualTo(list);
        org.assertj.core.api.Assertions.assertThat(exported.get("from")).isEqualTo(LocalDateTime.parse("2026-11-01T00:00:00"));
        org.assertj.core.api.Assertions.assertThat(exported.get("to")).isEqualTo(LocalDateTime.parse("2026-11-09T00:00:00"));
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            var sheet=book.getSheetAt(0);
            org.assertj.core.api.Assertions.assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains(actor.toString(),"2026-11-08 10:00");
            var headers=new ArrayList<String>();sheet.getRow(3).forEach(c->headers.add(c.getStringCellValue()));
            org.assertj.core.api.Assertions.assertThat(headers).containsExactly("createdAt","userId","name","type","result","screenId","target","ipAddress");
            org.assertj.core.api.Assertions.assertThat(sheet.getRow(4).getCell(6).getStringCellValue()).isEqualTo("users.xlsx");
        }
        verify(mapper).audit(argThat(w->actor.equals(w.get("actor"))&&"DOWNLOAD".equals(w.get("event"))));
    }
    @Test void userExportContainsOnlyAccountInventoryAndNeverCredentialsOrContactColumns()throws Exception {
        when(mapper.users(any())).thenReturn(List.of(user(actor)));
        byte[] bytes=mvc.perform(get("/api/admin/users/export").cookie(cookie).param("status","ACTIVE"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            var headers=new ArrayList<String>();book.getSheetAt(0).getRow(3).forEach(c->headers.add(c.getStringCellValue()));
            org.assertj.core.api.Assertions.assertThat(headers).containsExactly("userId","loginId","name","status","lastLoginAt","resignationDate");
        }
        verify(mapper).users(argThat(q->"ACTIVE".equals(q.get("status"))&&!q.containsKey("size")));
    }
    @Test void unknownUserIs404AndDatabaseFailureIsSanitized()throws Exception {
        mvc.perform(get("/api/admin/users/999999").cookie(cookie)).andExpect(status().isNotFound());
        when(mapper.roles()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private schema and connection"));
        mvc.perform(get("/api/admin/roles").cookie(cookie)).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }
}
