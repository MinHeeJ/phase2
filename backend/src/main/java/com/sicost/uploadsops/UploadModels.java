package com.sicost.uploadsops;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sicost.common.security.Sensitive;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class UploadModels {
    private UploadModels() {}
    public record Code(String group, String code, String name, String label) {}
    public record Fix(String type, String group) {}
    public record Issue(String severity, int rowNo, String column, String rawValue, String message, Fix fix) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(String fileName, int totalRows, int okRows, int errorRows, int warningRows,
                         List<Issue> items, String upldId, String token) {}
    public record Applied(String upldId, int appliedRows) {}
    public record Partner(int rowNo, String kind, String name, String bizNo, String ceo, String industry,
                          String contact, String phone, String email, @Sensitive String bank,
                          @Sensitive String account, @Sensitive String holder, String remark) {}
    public record Parsed(int header, List<Partner> rows, List<Issue> issues) {}
    public record Original(String fileName, String path) {}
    public record Ledger(String fileName, String path, Result result, String status, Long actor) {}
    public record IssueWrite(Long id, Issue issue) {}
    public record StoredIssue(String severity, int rowNo, String column, String rawValue, String message) {
        Issue issue() { return new Issue(severity,rowNo,column,rawValue,message,null); }
    }
    public record PartnerWrite(Long id, String no, Long uploadId, Partner row, Long actor) {}
    public record Access(String type, String fileName, String detail, Long actor) {}
    public record History(Long id, Long uploadId, String name, Long actor) {}
    public record Download(String fileName, byte[] bytes) {}
    static final class Validation {
        final Path path;
        final Result result;
        final List<Partner> rows;
        final Instant expires;
        boolean consumed;
        Validation(Path path, Result result, List<Partner> rows, Instant expires) {
            this.path=path;this.result=result;this.rows=rows;this.expires=expires;
        }
    }
}
