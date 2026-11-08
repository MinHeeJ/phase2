package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.admin.AdminController;
import com.sicost.common.security.*;
import java.util.Arrays;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.core.annotation.AnnotatedElementUtils;

class HandlerAuthorizationTest {
    final PermissionService permissions=mock(PermissionService.class);
    final HandlerAuthorizationInterceptor interceptor=new HandlerAuthorizationInterceptor(permissions);
    @AfterEach void clear() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @Test void everyExistingCommonCodeHandlerHasAnExplicitMapping() {
        var controller=mock(AdminController.class);
        var handlers=Arrays.stream(AdminController.class.getDeclaredMethods()).filter(m->AnnotatedElementUtils.hasAnnotation(m,RequestMapping.class)).toList();
        assertThat(handlers).isNotEmpty();
        for(var method:handlers) assertThat(interceptor.permission(new HandlerMethod(controller,method))).startsWith("UI-27.");
    }
    @Test void unmappedProtectedHandlerFailsClosed() throws Exception {
        AuthorizationTest.authenticate(11L);
        var handler=new HandlerMethod(new Object(),Object.class.getMethod("toString"));
        var request=new MockHttpServletRequest("GET","/api/unmapped");
        AuthorizationTest.status(()->interceptor.preHandle(request,new MockHttpServletResponse(),handler),403);verifyNoInteractions(permissions);
    }
    @Test void directAdminHandlerNeedsMappedActionEvenWithPrincipal() throws Exception {
        AuthorizationTest.authenticate(11L);
        var method=AdminController.class.getDeclaredMethod("createCodeGroup",String.class);
        var request=new MockHttpServletRequest("POST","/api/admin/code-groups");
        interceptor.preHandle(request,new MockHttpServletResponse(),new HandlerMethod(mock(AdminController.class),method));
        verify(permissions).can("UI-27.create");
    }
    @Test void postCheckUsesInquiryAndFileGenerationUsesDownload() {
        assertThat(ClosingActions.key("runClosingCheck")).isEqualTo("UI-14.read");
        assertThat(DashboardActions.key("exportDashboard")).isEqualTo("UI-16.download");
        assertThat(ClosingActions.key("reopenClosing")).isEqualTo("UI-14.reopen");
    }
}
