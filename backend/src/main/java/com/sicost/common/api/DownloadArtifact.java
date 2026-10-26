package com.sicost.common.api;

import java.util.Base64;
import java.util.Objects;

public record DownloadArtifact(String file_name, String media_type, String content_base64) {
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public static DownloadArtifact workbook(String fileName, byte[] bytes) {
        return new DownloadArtifact(Objects.requireNonNull(fileName), XLSX,
            Base64.getEncoder().encodeToString(Objects.requireNonNull(bytes)));
    }
}
