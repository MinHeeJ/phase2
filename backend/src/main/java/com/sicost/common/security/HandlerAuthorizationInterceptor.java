package com.sicost.common.security;

import com.sicost.common.ai.AiActions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class HandlerAuthorizationInterceptor implements HandlerInterceptor {
    private static final Map<String,String> ADMIN=Map.ofEntries(
        Map.entry("listCodeGroups","read"),Map.entry("listManagedCodes","read"),Map.entry("listExcelMaps","read"),
        Map.entry("listCodeHistory","read"),Map.entry("listCurrentParams","read"),Map.entry("listParamVersions","read"),
        Map.entry("createCodeGroup","create"),Map.entry("saveGroupCodes","update"),Map.entry("createExcelMap","create"),
        Map.entry("updateExcelMap","update"),Map.entry("saveParamVersion","update"));
    private static final Set<String> SELF=Set.of("GET /api/auth/me","GET /api/navigation","GET /api/menus","GET /api/me/permissions",
        "POST /api/auth/change-password","POST /api/auth/logout","POST /api/auth/activity");
    private final PermissionService permissions;
    public HandlerAuthorizationInterceptor(PermissionService permissions) { this.permissions=permissions; }
    public String permission(HandlerMethod handler) {
        var annotation=AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(),HandlerAuthorization.class);
        if(annotation==null) annotation=AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(),HandlerAuthorization.class);
        if(annotation!=null) return annotation.value();
        String operation=handler.getMethod().getName();
        if(handler.getBeanType().getName().equals("com.sicost.shared.SharedReadController")) return switch(operation) {
            case "getCodeOptions", "getEffectiveParam" -> "UI-27.read";
            case "getMonthClose" -> "UI-14.read";
            default -> null;
        };
        if(handler.getBeanType().getName().equals("com.sicost.admin.AdminController")) {
            String action=ADMIN.get(operation);return action==null?null:"UI-27."+action;
        }
        // Existing action registries classify by business operation, never by HTTP verb.
        for(Function<String,String> resolver:List.<Function<String,String>>of(MasterDataActions::key,ProjectActions::key,AssignmentActions::key,
            BillingActions::key,CostActions::key,DashboardActions::key,AiActions::key,ClosingActions::key)) {
            try { return resolver.apply(operation); } catch(IllegalArgumentException ignored) { /* Try the next existing registry. */ }
        }
        return null;
    }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object target) {
        if(!(target instanceof HandlerMethod handler)) return true;
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(SessionAuthenticationFilter.publicEntry(request.getMethod(),path)) return true;
        new CurrentUser().principal();
        if(handler.getBean() instanceof TargetedHandlerAuthorization targeted) {
            targeted.authorizeOperation(handler.getMethod().getName(),request);
            return true;
        }
        if(SELF.contains(request.getMethod()+" "+path)) return true;
        if(request.getMethod().equals("GET") && path.matches("/api/codes/[^/]+")) return true;
        if((request.getMethod().equals("GET") && (path.equals("/api/notifications") || path.equals("/api/notifications/unread-count") || path.matches("/api/notifications/[1-9][0-9]*"))) ||
            (request.getMethod().equals("PATCH") && path.matches("/api/notifications/[1-9][0-9]*/read")) ||
            (request.getMethod().equals("POST") && path.equals("/api/notifications/read-all"))) return true;
        String key=permission(handler);
        if(key==null) throw AuthorizationService.forbidden();
        permissions.can(key);
        return true;
    }
}
