package com.sicost.apiops;

public final class UploadResultModels {
    private UploadResultModels() {}

    public record UploadResult(long upload_id, String form_type, String month, int row_count,
                               int error_count, int warning_count, boolean can_apply,
                               long existing_salary_count, String overwrite_notice, String issues_url,
                               boolean normalized_rows_available) {}
}
