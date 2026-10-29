package com.sicost.dispatchattendance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class DispatchAttendanceModels {
    private DispatchAttendanceModels() {}
    public record Scope(String month, long projectId, LocalDate firstDay, LocalDate lastDay) {}
    public record Project(String cntrct_ty_cd, String stlm_mthd_cd) {}
    public record AttendanceInput(long asgn_id, long dev_id, BigDecimal work_day_qty,
            BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty) {}
    public record Source(long asgn_id, long dev_id, BigDecimal assignment_mm, boolean price_available,
            long assignment_count, boolean billed, BigDecimal work_day_qty, BigDecimal rglr_tm_qty,
            BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty) {}
    public record AttendanceRow(long asgn_id, long dev_id, BigDecimal work_day_qty,
            BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty,
            BigDecimal calculated_mm, BigDecimal assignment_mm, boolean price_available, List<String> warnings) {}
    public record AttendanceResult(String month, long project_id, List<AttendanceRow> items, List<String> warnings) {}
    public record Stored(long atnd_id, Long asgn_id, long dev_id, BigDecimal work_day_qty,
            BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty, Long upld_id) {}
    public record AttendanceWrite(Scope scope, AttendanceInput input, Long actor) {}
    public record AttendanceAudit(String id, String type, String before, String after, Long actor) {}
}
