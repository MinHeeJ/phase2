package com.sicost.projectsops;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ProjectAssignmentMonthModels {
    private ProjectAssignmentMonthModels() {}
    public record Query(long id, LocalDate start, LocalDate end, String firstMonth, String lastMonth) {}
    public record Cell(String work_ym, BigDecimal mm_qty) {}
    public record StoredMonth(long asgn_id, long dev_id, String dev_nm, String work_ym, BigDecimal mm_qty) {}
    public record Row(long asgn_id, long dev_id, String dev_nm, List<Cell> months, BigDecimal total_mm_qty) {}
    public record Result(long prj_id, int year, List<Row> items, List<Cell> month_totals) {}
}
