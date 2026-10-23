package com.sicost.uploadsops;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sicost.common.security.Sensitive;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class UploadModels {
    private UploadModels() {}
    public record Code(String group, String code, String name, String label) {}
    public record MappingFix(String type, String group) {}
    public record Issue(String severity, int rowNo, String column, String rawValue, String message, MappingFix fix) {
        public Issue(String severity, int rowNo, String column, String rawValue, String message) {
            this(severity,rowNo,column,rawValue,message,null);
        }
    }
    public record Validation(String fileName, int totalRows, int okRows, int errorRows, int warningRows,
        List<Issue> items, @JsonInclude(JsonInclude.Include.NON_NULL) String upldId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String token) {}
    public record Applied(String upldId, int appliedRows) {}
    public record PartnerRow(int rowNo, String kind, String name, String businessNo, String ceo, String industry,
        String contact, String phone, String email, @Sensitive String bank, @Sensitive String account,
        @Sensitive String depositor, String remark) {}
    public record Parsed(List<PartnerRow> rows, List<Issue> items) {}
    public record Original(String fileName, String path) {}
    public record Ledger(Original original, Validation result, String status, Long actor) {}
    public record IssueWrite(long id, Issue issue) {}
    public record PartnerWrite(long id, String no, PartnerRow row, long uploadId, Long actor) {}
    public record HistoryWrite(long id, long uploadId, String value, Long actor) {}
    public record AccessWrite(String type, String fileName, String detail, Long actor) {}
    public record Download(String fileName, byte[] bytes) {}
    static final class ValidatedFile {
        final Original original;
        final Parsed parsed;
        final Validation result;
        final Instant issuedAt;
        Applied completed;
        ValidatedFile(Original original, Parsed parsed, Validation result, Instant issuedAt) {
            this.original=original; this.parsed=parsed; this.result=result; this.issuedAt=issuedAt;
        }
    }
}
