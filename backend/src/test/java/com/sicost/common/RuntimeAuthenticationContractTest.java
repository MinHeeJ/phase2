package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

class RuntimeAuthenticationContractTest {
    private Map<?,?> contract() throws Exception {
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            return new Yaml().load(input);
        }
    }
    private Map<?,?> object(Map<?,?> source, String key) {
        assertThat(source.get(key)).isInstanceOf(Map.class);
        return (Map<?,?>) source.get(key);
    }
    @Test void durableFixtureDeclaresRuntimeLoginIdentityAndRole() throws Exception {
        var api = contract();
        var paths = object(api, "paths");
        var login = object(object(paths, "/api/auth/login"), "post");
        var identity = object(object(paths, "/api/auth/me"), "get");
        var menu = object(object(paths, "/api/billings"), "get");
        assertThat(login.get("operationId")).isEqualTo("createLogin");
        assertThat(identity.get("operationId")).isEqualTo("getCurrentUser");
        assertThat(menu.get("operationId")).isEqualTo("listBillings");
        var selected = Set.of("createLogin", "getCurrentUser", "listBillings");
        var counts = new HashMap<String,Integer>();
        paths.values().forEach(item -> ((Map<?,?>) item).forEach((method, value) -> {
            if (Set.of("get", "post", "put", "patch", "delete").contains(method) && value instanceof Map<?,?> operation) {
                var id = operation.get("operationId");
                if (id instanceof String name && selected.contains(name)) counts.merge(name, 1, Integer::sum);
            }
        }));
        selected.forEach(name -> assertThat(counts.get(name)).isEqualTo(1));
        assertThat(login.get("security")).isEqualTo(List.of());
        for (var operation : List.of(identity, menu)) {
            assertThat((List<?>) operation.get("security")).isNotEmpty();
            assertThat(((List<?>) operation.get("x-roles")).contains("재무 담당")).isTrue();
        }
        var components = object(api, "components");
        var cookie = object(object(components, "securitySchemes"), "sessionCookie");
        assertThat(cookie.get("type")).isEqualTo("apiKey");
        assertThat(cookie.get("in")).isEqualTo("cookie");
        assertThat(cookie.get("name")).isEqualTo("JSESSIONID");
        var role = object(object(components, "schemas"), "RoleCode");
        assertThat(((List<?>) role.get("enum")).contains("재무 담당")).isTrue();
        var request = object(object(object(object(login, "requestBody"), "content"), "application/json"), "schema");
        assertThat(request.get("required")).isEqualTo(List.of("loginId", "password"));
        var response = object(object(object(object(identity, "responses"), "200"), "content"), "application/json");
        var data = object(object(object(object(response, "schema"), "properties"), "data"), "properties");
        assertThat(object(data, "roles").get("type")).isEqualTo("array");
        assertThat(object(object(data, "roles"), "items").get("$ref")).isEqualTo("#/components/schemas/RoleCode");
    }
}
