package com.si.cost.shared;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.assertThat;

class PublicRuntimeContractTest {
    @Test void publicMenuAndHealthHaveNoAuthenticationDeclarations() throws Exception {
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            Map<String, Object> api = new Yaml().load(input);
            assertThat(api).doesNotContainKey("security");
            Map<?, ?> components = (Map<?, ?>) api.get("components");
            assertThat(components.containsKey("securitySchemes")).isFalse();
            Map<?, ?> paths = (Map<?, ?>) api.get("paths");
            for (var entry : Map.of("/api/menus", "getMenus", "/api/health", "getHealth").entrySet()) {
                Map<?, ?> path = (Map<?, ?>) paths.get(entry.getKey());
                Map<?, ?> get = (Map<?, ?>) path.get("get");
                assertThat(get.get("operationId")).isEqualTo(entry.getValue());
                // An empty security array is public; the first-menu contract requires absence.
                if (entry.getKey().equals("/api/menus")) {
                    assertThat(get.containsKey("security")).isFalse();
                }
                assertThat(get.containsKey("x-roles")).isFalse();
                assertThat(get.containsKey("parameters")).isFalse();
                assertThat(get.containsKey("requestBody")).isFalse();
            }
        }
    }
}
