package com.sicost.dispatchattendance2;

import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.uploadsops.PersonnelUploadModels.Result;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class DispatchAttendanceUploadModels {
    private DispatchAttendanceUploadModels() {}
    public record Scope(long projectId, String month, LocalDate first, LocalDate last) {}
    public record Project(long id, String type, String settlement, String enabled) {}
    public record Source(long asgn_id, long dev_id, String mng_no, String dev_nm,
                         BigDecimal assignment_mm, boolean price_available) {}
    public record Attendance(Long atnd_id, String work_ym, long prj_id, long dev_id, Long asgn_id,
                             BigDecimal work_day_qty, BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty,
                             BigDecimal mth_work_day_qty, Long upld_id) {}
    public record Preview(int row, String severity, Map<String,Object> attendance) {}
    public record Parsed(List<Preview> rows, List<Issue> issues, List<Attendance> attendance) {}
    public record Application(long upload_id, String form_type, int applied_count, String message) {}
    public record Apply(long upload_id, boolean confirm) {}
    public record UploadWrite(long id, Scope scope, String name, String path, Result result,
                              String status, String overwrite, Long actor) {}
    public record AttendanceWrite(Attendance row, long upload, Long actor) {}
    public record Audit(String id, String type, String before, String after, Long actor) {}
}
