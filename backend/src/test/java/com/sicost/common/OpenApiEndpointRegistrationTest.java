package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.sicost.admin.AdminController;
import com.sicost.admin.AdminService;
import com.sicost.common.health.HealthController;
import com.sicost.common.security.CurrentUser;
import com.sicost.partners.PartnerController;
import com.sicost.partners.PartnerService;
import com.sicost.partnersops.PartnerActivityController;
import com.sicost.partnersops.PartnerActivityService;
import com.sicost.salesparts.SalesPartController;
import com.sicost.salesparts.SalesPartService;
import com.sicost.salespartsops.SalesPartActionController;
import com.sicost.salespartsops.SalesPartActionService;
import com.sicost.shared.SharedReadController;
import com.sicost.shared.SharedReadService;
import com.sicost.uploadsops.UploadController;
import com.sicost.uploadsops.UploadService;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

@WebMvcTest(controllers = {
    HealthController.class, SharedReadController.class, AdminController.class,
    PartnerController.class, PartnerActivityController.class, SalesPartController.class,
    SalesPartActionController.class, UploadController.class
})
@Import(CurrentUser.class)
class OpenApiEndpointRegistrationTest {
    @Autowired RequestMappingHandlerMapping handlers;
    @MockBean SharedReadService sharedReadService;
    @MockBean AdminService adminService;
    @MockBean PartnerService partnerService;
    @MockBean PartnerActivityService partnerActivityService;
    @MockBean SalesPartService salesPartService;
    @MockBean SalesPartActionService salesPartActionService;
    @MockBean UploadService uploadService;

    @Test
    void durableOpenApiOperationsMatchRegisteredSpringEndpointsExactly() throws Exception {
        Map<String, Object> contract;
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract = new Yaml().load(input);
        }
        Set<String> expected = new TreeSet<>();
        Set<String> operationIds = new TreeSet<>();
        var paths = (Map<?, ?>) contract.get("paths");
        paths.forEach((path, item) -> ((Map<?, ?>) item).forEach((method, value) -> {
            if (Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace").contains(method)) {
                var operation = (Map<?, ?>) value;
                assertThat(operation.get("operationId")).isInstanceOf(String.class);
                assertThat(operationIds.add((String) operation.get("operationId")))
                    .as("unique operationId for %s %s", method, path).isTrue();
                assertThat(expected.add(method.toString().toUpperCase(Locale.ROOT) + " " + normalize(path.toString())))
                    .as("unique operation %s %s", method, path).isTrue();
                assertThat((Map<?, ?>) operation.get("responses")).isNotEmpty();
            }
        }));
        Set<String> actual = new TreeSet<>();
        handlers.getHandlerMethods().forEach((mapping, handler) -> {
            if (handler.getBeanType().getPackageName().startsWith("com.sicost")) {
                assertThat(mapping.getPatternValues()).as(handler.toString()).hasSize(1);
                assertThat(mapping.getMethodsCondition().getMethods()).as(handler.toString()).hasSize(1);
                mapping.getPatternValues().forEach(path -> mapping.getMethodsCondition().getMethods().forEach(method -> {
                    assertThat(actual.add(method.name() + " " + normalize(path)))
                        .as("one handler per operation: %s %s", method, path).isTrue();
                }));
            }
        });
        assertThat(expected).isNotEmpty();
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static String normalize(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }
}
