package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

class AnonymousSecurityContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void explicitAnonymousPolicyHasNoAuthenticationOrRoleRequirements() throws Exception {
        Map<String, Object> contract;
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract = new Yaml().load(input);
        }
        var components = (Map<String, Object>) contract.get("components");
        assertThat((Map<String, Object>) components.getOrDefault("securitySchemes", Map.of()))
            .isEmpty();
        assertThat((List<Object>) contract.getOrDefault("security", List.of())).isEmpty();
        var paths = (Map<String, Map<String, Object>>) contract.get("paths");
        for (var path : paths.values()) {
            for (var method : List.of("get", "post", "put", "patch", "delete", "head", "options", "trace")) {
                if (path.get(method) instanceof Map<?, ?> operation) {
                    assertThat(operation.get("security")).isIn(null, List.of());
                    assertThat(operation.containsKey("x-roles")).isFalse();
                }
            }
        }
        var firstMenu = (Map<String, Object>) paths.get("/api/admin/code-groups").get("get");
        assertThat(firstMenu.get("operationId")).isEqualTo("listCodeGroups");
        var health = (Map<String, Object>) paths.get("/api/health").get("get");
        assertThat(health.get("operationId")).isEqualTo("getHealth");
    }
}
