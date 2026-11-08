package com.sicost.authentication;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.*;

class RuntimeOpenApiContractTest {
    @SuppressWarnings("unchecked")
    @Test void loginIdentityAndFirstMenuUseDeclaredOperationsAndRoles() throws Exception {
        Map<String,Object> contract;
        try(var input=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract=new Yaml().load(input);
        }
        var paths=(Map<String,Map<String,Map<String,Object>>>)contract.get("paths");
        assertThat(paths.get("/api/auth/login").get("post").get("operationId")).isEqualTo("createLogin");
        assertThat(paths.get("/api/auth/me").get("get").get("operationId")).isEqualTo("getCurrentUser");
        assertThat(paths.get("/api/projects").get("get").get("operationId")).isEqualTo("listProjects");
        assertThat((List<String>)contract.get("x-roles")).contains("경영진");
        for(String path:List.of("/api/auth/me","/api/projects"))
            assertThat((List<String>)paths.get(path).get("get").get("x-roles")).contains("경영진");
    }
    @Test void loginFieldsAndIdentityRoleArrayAreDeclared() throws Exception {
        com.fasterxml.jackson.databind.JsonNode contract;
        try(var input=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(new Yaml().load(input));
        }
        var login=contract.path("paths").path("/api/auth/login").path("post");
        var fields=login.path("requestBody").path("content").path("application/json").path("schema").path("properties");
        assertThat(fields.path("loginId").path("type").asText()).isEqualTo("string");
        assertThat(fields.path("password").path("type").asText()).isEqualTo("string");
        var roles=contract.path("paths").path("/api/auth/me").path("get").path("responses").path("200")
            .path("content").path("application/json").path("schema").path("properties").path("data").path("properties").path("roles");
        assertThat(roles.path("type").asText()).isEqualTo("array");
        assertThat(roles.path("items").path("type").asText()).isEqualTo("string");
    }
}
