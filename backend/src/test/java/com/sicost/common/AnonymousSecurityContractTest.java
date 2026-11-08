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
    void publicHealthAndLoginRemainAnonymousWhileBusinessReadsRequireSession() throws Exception {
        Map<String, Object> contract;
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract = new Yaml().load(input);
        }
        assertThat((List<Object>) contract.get("security")).containsExactly(Map.of("serverSession", List.of()));
        var paths = (Map<String, Map<String, Object>>) contract.get("paths");
        for (String path : List.of("/api/auth/me", "/api/projects")) {
            var operation=(Map<String,Object>) paths.get(path).get("get");
            assertThat((List<String>)operation.get("x-roles")).contains("경영진");
            assertThat(operation.getOrDefault("security",contract.get("security")))
                .isEqualTo(List.of(Map.of("serverSession",List.of())));
        }
        var login=(Map<String,Object>)paths.get("/api/auth/login").get("post");
        assertThat(login.get("security")).isEqualTo(List.of());
        assertThat(login.get("operationId")).isEqualTo("createLogin");
        var health = (Map<String, Object>) paths.get("/api/health").get("get");
        assertThat(health.get("operationId")).isEqualTo("getHealth");
        assertThat(health.get("security")).isEqualTo(List.of());
    }
}
