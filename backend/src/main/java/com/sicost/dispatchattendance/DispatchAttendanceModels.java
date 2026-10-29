package com.sicost.dispatchattendance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class DispatchAttendanceModels {
    private DispatchAttendanceModels() {}
    public record Scope(long projectId, String month, LocalDate start, LocalDate end) {}
    public record Project(String cntrct_ty_cd, String stlm_mthd_cd) {}
    public record Input(long asgn_id, long dev_id, BigDecimal work_day_qty,
            BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty) {}
    public record Source(long asgn_id, long dev_id, BigDecimal work_day_qty,
            BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty,
            BigDecimal assignment_mm, boolean price_available) {}
    public record Row(long asgn_id, long dev_id, BigDecimal work_day_qty,
            BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty,
            BigDecimal calculated_mm, BigDecimal assignment_mm, boolean price_available,
            List<String> warnings) {}
    public record Result(String month, long project_id, List<Row> items, List<String> warnings) {}
    public record Write(Scope scope, Input row, Long actor) {}
    public record Key(Scope scope, long devId) {}
}
