package com.sicost.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/** Reads the packaged contract, never a runner-owned specification directory. */
public final class OpenApiFixture {
    private OpenApiFixture() {}

    public static void assertOperation(String path, String method, String operationId) throws IOException {
        Map<?, ?> document;
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            document = new Yaml().load(input);
        }
        var paths = (Map<?, ?>) document.get("paths");
        assertThat(paths.containsKey(path)).as("durable contract path %s", path).isTrue();
        var item = (Map<?, ?>) paths.get(path);
        assertThat(item.containsKey(method)).as("contract method %s %s", method, path).isTrue();
        var operation = (Map<?, ?>) item.get(method);
        assertThat(operation.get("operationId")).isEqualTo(operationId);
        assertThat(operation.get("responses")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) operation.get("responses")).keySet().stream()
            .map(Object::toString).anyMatch(status -> status.startsWith("2")))
            .as("operation declares a success response").isTrue();
    }
}
