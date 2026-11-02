package com.sicost.businessplan2;

import java.math.BigDecimal;
import java.util.List;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.common.security.Sensitive;

public final class BusinessPlanUploadModels {
    private BusinessPlanUploadModels() {}
    public record Plan(long id, String month, Long partId, @Sensitive long revenue, @Sensitive long profit, BigDecimal utilization) {}
    public record Preview(int row, String severity, Plan plan) {}
    public record Parsed(String year, List<Preview> rows, List<Issue> issues) {}
    public record Review(long id, String year, String name, String path, String checksum, Parsed parsed) {}
    public record Item(String field, String message, int row, String severity) {}
    public record Summary(String upload_id, int error_count, List<Item> items, int row_count,
            boolean can_apply, int applied_count, List<Preview> rows, String overwrite_notice) {}
    public record Apply(long uploadId, boolean confirm) {}
    public record PlanWrite(Plan row, long uploadId, Long actor) {}
    public record ReviewWrite(Review review, Summary result, String status, Long actor) {}
    public record Audit(String id, String type, String before, String after, Long actor) {}
}
