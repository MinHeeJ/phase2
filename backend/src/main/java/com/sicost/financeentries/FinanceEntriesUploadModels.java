package com.sicost.financeentries;

import com.sicost.entries.FinanceEntryModels.EntryInput;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import java.util.List;

public final class FinanceEntriesUploadModels {
    private FinanceEntriesUploadModels() {}
    public record Preview(int row, String severity, EntryInput entry) {}
    public record Parsed(String month, List<Preview> rows, List<Issue> issues) {}
    public record Review(long id, String month, String name, String path, String checksum, Parsed parsed) {}
    public record Item(String field, String message, int row, String severity, String raw_value) {}
    public record Summary(String upload_id, int error_count, int warning_count, List<Item> items,
            int row_count, boolean can_apply, int applied_count, int overwrite_count,
            List<Preview> rows, String overwrite_notice) {}
    public record ReviewWrite(Review review, Summary result, String status, Long actor) {}
    public record EntryWrite(EntryInput input, long uploadId, Long actor) {}
}
