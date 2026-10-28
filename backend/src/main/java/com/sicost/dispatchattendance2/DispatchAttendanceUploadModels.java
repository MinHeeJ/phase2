package com.sicost.dispatchattendance2;

import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import java.math.BigDecimal;
import java.util.List;

public final class DispatchAttendanceUploadModels {
    private DispatchAttendanceUploadModels() {}
    public record Scope(long projectId, String month) {}
    public record Project(long id, String number, String type, String settlement, String enabled) {}
    public record Assignment(long asgn_id, long dev_id, String mng_no, String dev_nm,
                             BigDecimal assignment_mm, Long bill_price) {}
    public record Attendance(Long atnd_id, Long asgn_id, long dev_id, BigDecimal work_day_qty,
                             BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty) {}
    public record Preview(int row, String severity, String management_number, String name,
                          Attendance attendance, BigDecimal calculated_mm, BigDecimal assignment_mm,
                          boolean price_available) {}
    public record Parsed(List<Preview> rows, List<Issue> issues) {}
    public record ApplyInput(long upload_id, boolean confirm) {}
    public record Applied(long upload_id, String form_type, int applied_count, String message) {}
    public record ReviewWrite(long id, Scope scope, String name, String path,
                             com.sicost.uploadsops.PersonnelUploadModels.Result result, String status) {}
    public record AttendanceWrite(Scope scope, Attendance input, long upload) {}
    public record Audit(String id, String type, String before, String after) {}
}
