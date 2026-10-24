package com.sicost.uploadsops;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sicost.partners.PartnerModels.PartnerSave;

import java.util.List;

public final class UploadModels {
    private UploadModels() {}
    public record MappingAction(String type, String group) {}
    public record UploadIssue(String severity, int rowNo, String column, String rawValue, String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) MappingAction fix) {}
    public record UploadValidationResult(String fileName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String upldId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String token,
        int totalRows, int okRows, int errorRows, int warningRows, List<UploadIssue> items) {}
    public record UploadApplyResult(String upldId, int appliedRows) {}
    public record UploadCode(String group, String code, String name) {}
    public record UploadMapping(String group, String label, String code) {}
    public record UploadSource(String fileName, String path) {}
    public record ParsedUpload(List<PartnerSave> rows, List<UploadIssue> items) {}
    public record UploadContext(String fileName, String path, ParsedUpload parsed) {}
    public record UploadWrite(UploadContext context, UploadValidationResult result, String status, Long actor) {}
    public record IssueWrite(long id, UploadIssue issue, String input) {}
    public record PartnerImport(long id, String no, PartnerSave input, long uploadId, Long actor) {}
    public record ImportHistory(long id, long uploadId, Long actor) {}
    public record UploadAccess(String type, String display, String detail, Long actor) {}
    public record UploadFile(String fileName, byte[] bytes) {}
}
