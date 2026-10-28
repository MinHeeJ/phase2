package com.sicost.dispatchattendance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class DispatchAttendanceModels {
    private DispatchAttendanceModels() {}
    public record Scope(String month, long projectId, LocalDate first, LocalDate last) {}
    public record Project(String type, String method, String enabled) {}
    public record Input(long asgn_id, long dev_id, BigDecimal work_day_qty,
        BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty) {}
    public record Row(long asgn_id, long dev_id, BigDecimal work_day_qty,
        BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty,
        BigDecimal calculated_mm, BigDecimal assignment_mm, boolean price_available,
        List<String> warnings) {}
    public record Result(String month, long project_id, List<Row> items, List<String> warnings) {}
    public record Assignment(long asgn_id, long dev_id, BigDecimal assignment_mm,
        boolean price_available) {}
    public record Stored(long atnd_id, Long asgn_id, long dev_id, BigDecimal work_day_qty,
        BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty) {}
    public record Write(Scope scope, Input item, Long actor) {}
    public record Audit(String id, String type, String before, String after, Long actor) {}
}
