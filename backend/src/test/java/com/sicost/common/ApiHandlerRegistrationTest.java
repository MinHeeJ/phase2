package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sicost.admin.AdminController;
import com.sicost.authentication.AuthenticationController;
import com.sicost.contacts.BusinessContactController;
import com.sicost.developersops.DeveloperLifecycleController;
import com.sicost.notifications.NotificationController;
import com.sicost.partnersops.PartnerLifecycleController;
import com.sicost.projectsops.ProjectLifecycleController;
import com.sicost.salespartsops.SalesPartLifecycleController;
import com.sicost.shared.SharedReadController;
import com.sicost.systemmanagement.NavigationController;
import com.sicost.systemmanagement.SystemManagementController;
import com.sicost.uploadsops.UploadController;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Registration coverage only; service/security behavior is exercised in the owning API tests. */
class ApiHandlerRegistrationTest {
    private RequestMappingHandlerMapping mappings;

    @BeforeEach void registerActualControllers() {
        var context = new StaticWebApplicationContext();
        context.setServletContext(new MockServletContext());
        for (Class<?> controller : new Class<?>[] {
                AdminController.class, AuthenticationController.class, BusinessContactController.class,
                DeveloperLifecycleController.class, NotificationController.class, PartnerLifecycleController.class,
                ProjectLifecycleController.class, SalesPartLifecycleController.class, SharedReadController.class,
                NavigationController.class, SystemManagementController.class, UploadController.class }) {
            context.getBeanFactory().registerSingleton(controller.getName(), mock(controller));
        }
        mappings = new RequestMappingHandlerMapping();
        mappings.setApplicationContext(context);
        mappings.afterPropertiesSet();
    }

    static Stream<Arguments> operations() {
        return Stream.of(
            routes(SystemManagementController.class, """
                GET /api/admin/access-history
                GET /api/admin/access-history/export
                GET /api/admin/menus
                GET /api/admin/roles
                GET /api/admin/roles/731
                GET /api/admin/roles/731/permissions
                GET /api/admin/users
                GET /api/admin/users/export
                GET /api/admin/users/731
                PATCH /api/admin/menus/731
                PATCH /api/admin/roles/731
                PATCH /api/admin/users/731
                POST /api/admin/menus
                POST /api/admin/roles
                POST /api/admin/roles/731/copy
                POST /api/admin/users
                POST /api/admin/users/731/disable
                POST /api/admin/users/731/password-reset
                POST /api/admin/users/731/unlock
                PUT /api/admin/menus/reorder
                PUT /api/admin/roles/731/permissions
                PUT /api/admin/users/731/assignments
                PUT /api/admin/users/731/roles
                """),
            routes(AdminController.class, """
                GET /api/admin/code-groups
                GET /api/admin/code-groups/JOB/codes
                GET /api/admin/code-history
                GET /api/admin/excel-maps
                GET /api/admin/params
                GET /api/admin/params/VAT_RATE/versions
                PATCH /api/admin/excel-maps/731
                POST /api/admin/code-groups
                POST /api/admin/excel-maps
                PUT /api/admin/code-groups/JOB/codes
                PUT /api/admin/params/VAT_RATE/versions/2026-11
                """),
            routes(AuthenticationController.class, "GET /api/auth/me\nPOST /api/auth/activity"),
            routes(BusinessContactController.class, "GET /api/developers/731/contact\nGET /api/partners/731/contact"),
            routes(DeveloperLifecycleController.class, "GET /api/developers/export\nGET /api/developers/options\nPOST /api/developers/disable"),
            routes(NavigationController.class, "GET /api/me/permissions\nGET /api/navigation"),
            routes(SharedReadController.class, "GET /api/codes/JOB\nGET /api/menus\nGET /api/month-close"),
            routes(NotificationController.class, "GET /api/notifications/unread-count\nPOST /api/notifications/read-all"),
            routes(PartnerLifecycleController.class, "GET /api/partners/export\nGET /api/partners/options\nPOST /api/partners/disable"),
            routes(ProjectLifecycleController.class, "GET /api/projects/export\nGET /api/projects/options"),
            routes(SalesPartLifecycleController.class, "GET /api/sales-parts/export\nGET /api/sales-parts/options"),
            routes(UploadController.class, """
                GET /api/uploads/templates/B.7
                GET /api/uploads/templates/B.8
                POST /api/uploads/validate
                POST /api/uploads/validated/registration-proof/apply
                """)
        ).flatMap(stream -> stream);
    }

    private static Stream<Arguments> routes(Class<?> controller, String operations) {
        return operations.lines().filter(line -> !line.isBlank()).map(line -> {
            String[] operation = line.strip().split(" ", 2);
            return Arguments.of(operation[0], operation[1], controller);
        });
    }

    @ParameterizedTest(name = "{0} {1} resolves to {2}")
    @MethodSource("operations")
    void resolvesToOwningControllerRatherThanVariableRoute(String method, String path, Class<?> owner) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Accept", "application/json");
        if (path.equals("/api/uploads/validate")) request.setContentType("multipart/form-data; boundary=registration");
        var chain = mappings.getHandler(request);
        assertThat(chain).as("%s %s must have a handler", method, path).isNotNull();
        assertThat(chain.getHandler()).isInstanceOf(HandlerMethod.class);
        var handler = (HandlerMethod) chain.getHandler();
        assertThat(handler.getBeanType()).isEqualTo(owner);
        // Static endpoints such as /users/export must not be swallowed by /users/{id}.
        if (path.endsWith("/export") || path.endsWith("/options") || path.endsWith("/reorder")) {
            assertThat(handler.getMethod().getName()).doesNotMatch("user|role|menu|getDeveloper|getPartner|get");
        }
    }
}
