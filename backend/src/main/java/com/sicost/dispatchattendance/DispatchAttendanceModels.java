package com.sicost.dispatchattendance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class DispatchAttendanceModels {
    private DispatchAttendanceModels() {}
    public record Scope(String month, long projectId, LocalDate first, LocalDate last) {}
    public record Project(String type, String method, String enabled) {}
    public record Input(Long asgn_id, Long dev_id, BigDecimal work_day_qty,
        BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty) {}
    public record Batch(List<Input> items) {}
    public record Source(Long asgn_id, Long dev_id, BigDecimal assignment_mm, Boolean price_available,
        BigDecimal work_day_qty, BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty,
        BigDecimal mth_work_day_qty) {}
    public record Row(Long asgn_id, Long dev_id, BigDecimal work_day_qty, BigDecimal rglr_tm_qty,
        BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty, BigDecimal calculated_mm,
        BigDecimal assignment_mm, boolean price_available, List<String> warnings) {}
    public record Result(String month, long project_id, List<Row> items, List<String> warnings) {}
    public record Write(Scope scope, Input item, Long actor) {}
    public record Audit(String id, String type, String before, String after, Long actor) {}
}
