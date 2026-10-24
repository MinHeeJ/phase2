package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.sicost.admin.*;
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
    SalesPartLifecycleController.class, UploadController.class})
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

    @Test void everyDurableOperationHasOneRegisteredHandlerWithMatchingOperationId() throws Exception {
        Map<?,?> contract;
        try(var input=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract=new Yaml().load(input);
        }
        Map<String,String> expected=new TreeMap<>();
        Map<?,?> paths=(Map<?,?>)contract.get("paths");
        assertThat(paths).isNotEmpty();
        paths.forEach((path,item)->((Map<?,?>)item).forEach((method,definition)->{
            if(Set.of("get","post","put","patch","delete").contains(method.toString())) {
                var operation=(Map<?,?>)definition;
                assertThat(operation.get("operationId")).isInstanceOf(String.class);
                assertThat(operation.get("responses")).isInstanceOf(Map.class);
                expected.put(method.toString().toUpperCase(Locale.ROOT)+" "+path,
                    operation.get("operationId").toString());
            }
        }));
        Map<String,String> actual=new TreeMap<>();
        handlers.getHandlerMethods().forEach((mapping,handler)->{
            if(mapping.getPatternValues().stream().anyMatch(path->path.startsWith("/api/"))) {
                assertThat(mapping.getPatternValues()).as("one path per operation").hasSize(1);
                assertThat(mapping.getMethodsCondition().getMethods()).as("one method per operation").hasSize(1);
                String path=mapping.getPatternValues().iterator().next();
                String method=mapping.getMethodsCondition().getMethods().iterator().next().name();
                assertThat(actual.put(method+" "+path,handler.getMethod().getName())).isNull();
            }
        });
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }
}
