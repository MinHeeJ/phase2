package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.sicost.admin.*;
import com.sicost.apiops.*;
import com.sicost.developers.*;
import com.sicost.developersops.*;
import com.sicost.workstatus.*;
import com.sicost.common.health.HealthController;
import com.sicost.common.security.*;
import com.sicost.partners.*;
import com.sicost.partnersops.*;
import com.sicost.salesparts.*;
import com.sicost.salespartsops.*;
import com.sicost.shared.*;
import com.sicost.uploadsops.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

@WebMvcTest(controllers={AdminController.class, SharedReadController.class, HealthController.class,
    PartnerController.class, PartnerLifecycleController.class, SalesPartController.class,
    SalesPartLifecycleController.class, UploadController.class, DeveloperController.class,
    DeveloperLifecycleController.class, WorkStatusController.class, UploadResultController.class})
@Import(CurrentUser.class)
class OpenApiHandlerContractTest {
    @Autowired RequestMappingHandlerMapping handlers;
    @MockBean AdminService admin;
    @MockBean SharedReadService shared;
    @MockBean PartnerService partners;
    @MockBean PartnerLifecycleService partnerLifecycle;
    @MockBean SalesPartService salesParts;
    @MockBean SalesPartLifecycleService salesPartLifecycle;
    @MockBean SalesPartExportService exports;
    @MockBean UploadService uploads;
    @MockBean PermissionService permissions;
    @MockBean DeveloperService developers;
    @MockBean DeveloperLifecycleService developerLifecycle;
    @MockBean WorkStatusService workStatuses;
    @MockBean UploadResultService uploadResults;

    @Test void everyDurableOperationHasOneRegisteredLiteralHandler() throws Exception {
        Map<?,?> contract;
        try(var input=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract=new Yaml().load(input);
        }
        Set<String> expected=new TreeSet<>();
        Map<?,?> paths=(Map<?,?>)contract.get("paths");
        assertThat(paths).isNotEmpty();
        paths.forEach((path,item)->((Map<?,?>)item).forEach((method,definition)->{
            if(Set.of("get","post","put","patch","delete").contains(method.toString())) {
                var operation=(Map<?,?>)definition;
                assertThat(operation.get("operationId")).isInstanceOf(String.class);
                assertThat(operation.get("responses")).isInstanceOf(Map.class);
                expected.add(method.toString().toUpperCase(Locale.ROOT)+" "+normalize(path.toString()));
            }
        }));
        Map<String,String> actual=new TreeMap<>();
        handlers.getHandlerMethods().forEach((mapping,handler)->{
            if(mapping.getPatternValues().stream().anyMatch(path->path.startsWith("/api/"))) {
                assertThat(mapping.getPatternValues()).as("one path per operation").hasSize(1);
                assertThat(mapping.getMethodsCondition().getMethods()).as("one method per operation").hasSize(1);
                String path=mapping.getPatternValues().iterator().next();
                String method=mapping.getMethodsCondition().getMethods().iterator().next().name();
                assertThat(actual.put(method+" "+normalize(path),handler.getMethod().getName())).isNull();
            }
        });
        // Existing phases expose additional APIs; they must not hide a missing contract operation.
        assertThat(actual.keySet()).containsAll(expected);
    }
    private static String normalize(String path) { return path.replaceAll("\\{[^/{}]+}", "{}"); }
}
