package com.sicost.dispatchattendance2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.uploadsops.PersonnelUploadModels.Result;

public final class DispatchAttendanceUploadModels {
    private DispatchAttendanceUploadModels() {}
    public record Scope(long projectId, String month, LocalDate first, LocalDate last) {}
    public record Project(String type, String basis, String enabled) {}
    public record Assignment(long asgn_id, long dev_id, String mng_no, String dev_nm,
        BigDecimal assignment_mm, boolean price_available) {}
    public record Attendance(Long atnd_id, long asgn_id, long dev_id, BigDecimal work_day_qty,
        BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty) {}
    public record Preview(int row, String severity, Attendance attendance, BigDecimal calculated_mm,
        BigDecimal assignment_mm, List<String> warnings) {}
    public record Parsed(List<Preview> rows, List<Issue> issues) {}
    public record Applied(long upload_id, String form_type, int applied_count, String message) {}
    public record ReviewWrite(long id, Scope scope, String name, String path, Result result, Long actor) {}
    public record AttendanceWrite(Scope scope, Attendance row, long upload, Long actor) {}
}
