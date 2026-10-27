package com.sicost.months;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class AssignmentMonthsModels {
    private AssignmentMonthsModels() {}

    public record AssignmentState(long asgn_id, long prj_id, long dev_id, LocalDate bgn_dt,
        LocalDate end_dt, BigDecimal asgn_rt, String asgn_stts_cd, String prj_stts_cd,
        String use_yn, LocalDateTime updated_at) {}
    public record MonthRow(String work_ym, BigDecimal auto_mm_qty, BigDecimal mm_qty,
        String mnl_yn, BigDecimal person_month_mm_qty, String closed_yn) {}
    public record Warning(String code, String message, String work_ym) {}
    public record MonthsResult(long asgn_id, LocalDateTime updated_at, List<MonthRow> items,
        List<Warning> warnings) {}
    public record MonthInput(String work_ym, BigDecimal mm_qty) {}
    public record MonthsInput(LocalDateTime updated_at, List<MonthInput> months) {}
    public record MonthWrite(long id, String month, BigDecimal quantity, String manual, Long actor) {}
    public record MonthAudit(String id, String month, String before, String after, Long actor) {}
}
