package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.persistence.SchemaValidator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SchemaArtifactTest {
    private String read(String resource) throws Exception {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test void normalizedSnapshotPreservesEveryExecutableStatement() throws Exception {
        String original = read("schema/original.sql");
        String expected = original.replaceAll("(?m)^DROP SCHEMA[^;]+;\\s*\\n", "")
            .replaceAll("\\bCREATE (TABLE|INDEX|SEQUENCE) (?!IF NOT EXISTS)", "CREATE $1 IF NOT EXISTS ");
        String actual = read("db/migration/V1__schema.sql");
        assertThat(actual.substring(0, actual.indexOf("\n-- Business, reference and audit metadata"))).isEqualTo(expected);
        assertThat(read("schema.sql")).isEqualTo(actual);
        assertThat(actual.lines().filter(s -> s.startsWith("CREATE TABLE IF NOT EXISTS ")).count()).isEqualTo(80);
        assertThat(actual.lines().filter(s -> s.startsWith("CREATE SCHEMA ")).count()).isEqualTo(11);
    }
    @Test void metadataCoversAllScopedColumnsAndCompositeKeys() throws Exception {
        var mapper = new ObjectMapper();
        var tables = mapper.readValue(read("schema-metadata.json"), SchemaValidator.Table[].class);
        assertThat(tables).hasSize(9);
        var parameter = java.util.Arrays.stream(tables).filter(t -> t.table().equals("cmn.oprt_std")).findFirst().orElseThrow();
        assertThat(parameter.primaryKey()).containsExactly("oprt_std_id", "aply_bgn_ym");
        assertThat(parameter.columns()).anySatisfy(c -> { assertThat(c.get("name")).isEqualTo("num_val"); assertThat(c.get("type")).isEqualTo("DECIMAL(18,4)"); });
    }
}
