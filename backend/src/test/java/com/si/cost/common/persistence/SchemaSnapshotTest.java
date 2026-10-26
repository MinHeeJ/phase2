package com.si.cost.common.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class SchemaSnapshotTest {
    @Test void migrationPreservesPhysicalSnapshotWithOnlyApprovedTransformations() throws Exception {
        byte[] original = new ClassPathResource("contracts/schema-original.sql").getInputStream().readAllBytes();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)))
                .isEqualTo("f654190eb098a554f24d79a43aae594f1b2ca2ac7df957c0af62a0c90931a32a");
        String expected = new String(original, StandardCharsets.UTF_8)
                .replaceAll("(?m)^DROP SCHEMA[^\\n]*\\n", "")
                .replaceAll("(?m)^CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ")
                .replaceAll("(?m)^CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
                .replaceAll("(?m)[\\t ]+$", "");
        String actual = new String(new ClassPathResource("db/migration/V1__schema.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(actual).isEqualTo(expected);
    }
}
