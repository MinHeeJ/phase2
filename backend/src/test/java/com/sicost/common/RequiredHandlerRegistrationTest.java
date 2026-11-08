package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.admin.*;
import com.sicost.common.security.*;
import com.sicost.developersops.*;
import com.sicost.projectsops.*;
import com.sicost.shared.*;
import com.sicost.systemadmin.*;
import com.sicost.uploadsops.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequiredHandlerRegistrationTest {
    @Test void allReportedMissingOperationsAreRegisteredExactlyOnce() {
        var json=new ObjectMapper();
        var mvc=MockMvcBuilders.standaloneSetup(
            new SharedReadController(mock(SharedReadService.class)),
            new AdminController(mock(AdminService.class),mock(CurrentUser.class),json),
            new DeveloperLifecycleController(mock(DeveloperLifecycleService.class),json),
            new ProjectLifecycleController(mock(ProjectLifecycleService.class)),
            new UploadController(mock(UploadService.class),mock(PermissionService.class)),
            new AdministrationController(mock(AdministrationService.class))).build();
        var mapping=mvc.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class);
        Map<String,List<String>> registered=new HashMap<>();
        mapping.getHandlerMethods().forEach((route,handler)->route.getMethodsCondition().getMethods().forEach(method->
            route.getPatternValues().forEach(path->registered.computeIfAbsent(method.name()+" "+normalize(path),k->new ArrayList<>()).add(handler.getMethod().getName()))));
        var expected=Map.ofEntries(
            Map.entry("GET /api/month-close","getMonthClose"),
            Map.entry("GET /api/admin/code-groups","listCodeGroups"),
            Map.entry("POST /api/admin/code-groups","createCodeGroup"),
            Map.entry("GET /api/admin/code-groups/{}/codes","listManagedCodes"),
            Map.entry("PUT /api/admin/code-groups/{}/codes","saveGroupCodes"),
            Map.entry("GET /api/admin/excel-maps","listExcelMaps"),
            Map.entry("POST /api/admin/excel-maps","createExcelMap"),
            Map.entry("PATCH /api/admin/excel-maps/{}","updateExcelMap"),
            Map.entry("GET /api/admin/code-history","listCodeHistory"),
            Map.entry("GET /api/admin/params","listCurrentParams"),
            Map.entry("GET /api/admin/params/{}/versions","listParamVersions"),
            Map.entry("PUT /api/admin/params/{}/versions/{}","saveParamVersion"),
            Map.entry("POST /api/developers/disable","updateDevelopersDisabled"),
            Map.entry("GET /api/developers/options","listDeveloperOptions"),
            Map.entry("GET /api/developers/export","getDeveloperExport"),
            Map.entry("POST /api/uploads/validate","createPersonnelUploadValidation"),
            Map.entry("GET /api/uploads/templates/{}","getUploadTemplate"),
            Map.entry("GET /api/projects/export","getProjectExport"),
            Map.entry("GET /api/projects/options","listProjectOptions"),
            Map.entry("GET /api/admin/access-history","listAccessHistory"),
            Map.entry("GET /api/admin/users/export","getAdminUsersExport"),
            Map.entry("GET /api/admin/access-history/export","getAccessHistoryExport"));
        expected.forEach((route,handler)->assertThat(registered.get(route)).as(route).containsExactly(handler));
    }
    private static String normalize(String path) { return path.replaceAll("\\{[^}]+}","{}"); }
}
