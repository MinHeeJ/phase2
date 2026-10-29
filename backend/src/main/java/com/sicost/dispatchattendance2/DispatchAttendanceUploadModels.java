package com.sicost.dispatchattendance2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.uploadsops.PersonnelUploadModels.Result;

public final class DispatchAttendanceUploadModels {
    private DispatchAttendanceUploadModels() {}
    public record Scope(long projectId, String month, LocalDate firstDay, LocalDate lastDay) {}
    public record Project(String cntrct_ty_cd, String stlm_mthd_cd, String use_yn) {}
    public record Source(long asgn_id, long dev_id, String mng_no, String dev_nm,
            BigDecimal assignment_mm, boolean price_available) {}
    public record Attendance(long asgn_id, long dev_id, BigDecimal work_day_qty,
            BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty,
            BigDecimal calculated_mm, BigDecimal assignment_mm, boolean price_available,
            List<String> warnings) {}
    public record Preview(int row, String severity, Attendance attendance) {}
    public record Parsed(List<Preview> rows, List<Issue> issues) {}
    public record Review(long id, Scope scope, String name, String path, String checksum, Parsed parsed) {}
    public record ApplyResult(long upload_id, String form_type, int applied_count, String message) {}
    public record Stored(long atnd_id, long dev_id, Long asgn_id, BigDecimal work_day_qty,
            BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty, Long upld_id) {}
    public record AttendanceWrite(Scope scope, Attendance row, long uploadId, Long actor) {}
    public record ReviewWrite(Review review, Result result, String status, Long actor) {}
    public record Audit(String id, String type, String before, String after, Long actor) {}
}
