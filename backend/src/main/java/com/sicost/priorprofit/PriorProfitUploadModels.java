package com.sicost.priorprofit;

import java.util.List;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.common.security.Sensitive;

public final class PriorProfitUploadModels {
    private PriorProfitUploadModels() {}
    public record Snapshot(String month, @Sensitive long revenue, @Sensitive long allocatedCost,
            @Sensitive long idleCost, @Sensitive long directExpense, @Sensitive long sgaInput,
            @Sensitive long indirectLabor, @Sensitive long researchCost, @Sensitive long adjustment,
            @Sensitive long nonOperatingIncome, @Sensitive long nonOperatingExpense,
            @Sensitive long corporateTax, @Sensitive Long allocatedRevenue) {}
    public record Detail(String month, String code, @Sensitive long amount) {}
    public record Preview(int row, String severity, Snapshot profit) {}
    public record DetailPreview(int row, String severity, Detail detail) {}
    public record Parsed(List<Preview> rows, List<DetailPreview> details, List<Issue> issues) {}
    public record Review(long id, String name, String path, String checksum, Parsed parsed) {}
    public record Item(String field, String message, int row, String severity) {}
    public record Summary(String upload_id, int error_count, List<Item> items, int row_count,
            boolean can_apply, int applied_count, List<Preview> rows, List<DetailPreview> details, String overwrite_notice) {}
    public record Apply(long uploadId, boolean confirm) {}
    public record SnapshotWrite(Snapshot row, long uploadId, Long actor) {}
    public record ReviewWrite(Review review, Summary result, String status, Long actor) {}
    public record Audit(String table, String id, String type, String before, String after, Long actor) {}
}
