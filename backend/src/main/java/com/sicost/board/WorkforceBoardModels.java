package com.sicost.board;

import com.sicost.assignments.AssignmentModels.Item;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class WorkforceBoardModels {
    private WorkforceBoardModels() {}
    public record Query(int year, String job, String grade, String skill, String emp_se_cd,
        boolean free_only, String sort, int page, int size, LocalDate start, LocalDate end) {}
    public record Person(long dev_id, String dev_nm, String mng_no, String job_cd,
        String grd_cd, LocalDate rsgn_dt) {}
    public record Leave(long dev_id, String work_stts_cd, LocalDate bgn_dt, LocalDate end_dt) {}
    public record MonthlyAssignment(long asgn_id, String month, BigDecimal mm) {}
    public record Temporary(long id, long need_id, long dev_id, String bgn_ym, String end_ym,
        BigDecimal mth_mm_qty, String tmpr_asgn_src_cd, BigDecimal mtch_scr, String status,
        Long asgn_id, String oppt_nm, List<String> warnings) {}
    public record TemporarySource(long id, long need_id, long dev_id, String bgn_ym, String end_ym,
        BigDecimal mth_mm_qty, String tmpr_asgn_src_cd, BigDecimal mtch_scr, String status,
        Long asgn_id, String oppt_nm) {
        public Temporary item() {
            return new Temporary(id,need_id,dev_id,bgn_ym,end_ym,mth_mm_qty,
                tmpr_asgn_src_cd,mtch_scr,status,asgn_id,oppt_nm,List.of());
        }
    }
    public record Cell(String month, BigDecimal confirmed_mm, BigDecimal temporary_mm,
        int assignment_count, String status, LocalDate withdrawal_date, BigDecimal fill_ratio,
        List<Item> assignments, List<Temporary> temp_assignments, List<String> warnings) {}
    public record BoardRow(long dev_id, String dev_nm, String mng_no, String job_cd,
        String grd_cd, List<Cell> months) {}
    public record MonthlyTotal(String month, long free_people, BigDecimal remaining_mm) {}
    public record BoardPage(List<BoardRow> items, int page, int size, long total,
        List<MonthlyTotal> monthly_totals) {}
    public record Snapshot(List<BoardRow> rows, List<MonthlyTotal> monthly_totals) {}
    public record BoardDownload(String filename, String detail, Long actor) {}
}
