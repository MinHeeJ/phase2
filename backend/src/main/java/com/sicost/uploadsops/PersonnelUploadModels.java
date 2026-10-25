package com.sicost.uploadsops;

import java.util.List;
import java.util.Map;
import com.sicost.common.security.Sensitive;

public final class PersonnelUploadModels {
    private PersonnelUploadModels() {}
    public record Issue(int row, String column, String severity, String message, String raw_value,
                        String normalized_value, boolean mapping_allowed) {}
    public record Preview(int row, String severity, Map<String,Object> developer, @Sensitive Map<String,Object> salary) {}
    public record Parsed(List<Preview> rows, List<Issue> issues) {}
    public record Result(long upload_id, String form_type, String month, int row_count, int error_count,
                         int warning_count, boolean can_apply, long existing_salary_count, String overwrite_notice,
                         String issues_url, boolean normalized_rows_available) {}
    public record Application(long upload_id, String form_type, String month, int applied_count, String ovwr_yn,
                              List<Long> developer_ids, @Sensitive List<Map<String,Object>> monthly_costs, String message) {}
    public record ReviewWrite(long id, String form, String month, String name, String path, Result result, String overwrite, Long actor) {}
    public record DeveloperWrite(long id, String number, Map<String,Object> input, long upload, Long actor) {}
    public record SalaryWrite(long developer, String month, Map<String,Object> input, long upload, Long actor) {}
    public record ChildWrite(long developer, String code) {}
    public record ExpenseWrite(long developer, String month, String code, long amount, long upload, Long actor) {}
    public record HistoryWrite(String table, String id, String type, String reason, String after, Long actor) {}
}
