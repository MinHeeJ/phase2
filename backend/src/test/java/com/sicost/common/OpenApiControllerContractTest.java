package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.sicost.admin.AdminController;
import com.sicost.admin.AdminService;
import com.sicost.common.health.HealthController;
import com.sicost.common.security.CurrentUser;
import com.sicost.partners.PartnerController;
import com.sicost.partners.PartnerService;
import com.sicost.partnersops.PartnerLifecycleController;
import com.sicost.partnersops.PartnerLifecycleService;
import com.sicost.salesparts.SalesPartController;
import com.sicost.salesparts.SalesPartService;
import com.sicost.salespartsops.SalesPartExcelService;
import com.sicost.salespartsops.SalesPartManagementController;
import com.sicost.salespartsops.SalesPartManagementService;
import com.sicost.shared.SharedReadController;
import com.sicost.shared.SharedReadService;
import com.sicost.uploadsops.UploadController;
import com.sicost.uploadsops.UploadService;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

@WebMvcTest(controllers={AdminController.class, HealthController.class, PartnerController.class,
    PartnerLifecycleController.class, SalesPartController.class, SalesPartManagementController.class,
    SharedReadController.class, UploadController.class})
class OpenApiControllerContractTest {
    @Autowired RequestMappingHandlerMapping handlers;
    @MockBean AdminService admin;
    @MockBean CurrentUser user;
    @MockBean PartnerService partners;
    @MockBean PartnerLifecycleService partnerLifecycle;
    @MockBean SalesPartService salesParts;
    @MockBean SalesPartManagementService salesPartManagement;
    @MockBean SalesPartExcelService salesPartExcel;
    @MockBean SharedReadService shared;
    @MockBean UploadService uploads;

    private static String canonical(String path) { return path.replaceAll("\\{[^}]+}", "{}"); }

    @Test void everyDurableOpenApiOperationHasExactlyOneRegisteredLiteralHandler() throws Exception {
        Map<?,?> document;
        try(var input=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            document=new Yaml().load(input);
        }
        Map<?,?> paths=(Map<?,?>)document.get("paths");
        assertThat(paths).isNotEmpty();
        Set<String> expected=new TreeSet<>(), operationIds=new HashSet<>();
        Set<String> verbs=Set.of("get","post","put","patch","delete","head","options");
        paths.forEach((path,item)->((Map<?,?>)item).forEach((method,value)->{
            if(!verbs.contains(method.toString())) return;
            Map<?,?> operation=(Map<?,?>)value;
            assertThat(operation.get("operationId")).isInstanceOf(String.class);
            assertThat(operationIds.add(operation.get("operationId").toString()))
                .as("unique operationId for %s %s",method,path).isTrue();
            assertThat((Map<?,?>)operation.get("responses")).isNotEmpty();
            assertThat(expected.add(method.toString().toUpperCase(Locale.ROOT)+" "+canonical(path.toString())))
                .as("unique OpenAPI method/path for %s %s",method,path).isTrue();
        }));
        Set<String> actual=new TreeSet<>();
        handlers.getHandlerMethods().forEach((mapping,handler)->{
            if(mapping.getPatternValues().stream().noneMatch(p->p.startsWith("/api/"))) return;
            assertThat(handler.getBeanType().getPackageName()).startsWith("com.sicost");
            assertThat(mapping.getPatternValues()).as("one literal path per handler: %s",handler).hasSize(1);
            assertThat(mapping.getMethodsCondition().getMethods()).as("one HTTP verb per handler: %s",handler).hasSize(1);
            String path=mapping.getPatternValues().iterator().next();
            String method=mapping.getMethodsCondition().getMethods().iterator().next().name();
            assertThat(actual.add(method+" "+canonical(path))).as("unique registered operation: %s %s",method,path).isTrue();
        });
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
