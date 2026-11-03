package com.sicost.withdrawalsops;

import java.math.BigDecimal;
import java.util.List;

public final class NextSlotModels {
    private NextSlotModels() {}
    public record Profile(long asgn_id, long dev_id, String grd_cd, Long cust_ptnr_id) {}
    public record Need(long need_id, long oppt_id, String oppt_nm, String job_cd,
            String grd_cd, String bgn_ym, String end_ym, int remaining_slots,
            Long cust_ptnr_id, BigDecimal win_prblt_rt) {}
    public record ScoreComponents(int job, int grade, int gap, int customer, int probability) {
        public int total() { return job + grade + gap + customer + probability; }
    }
    public record NextSlot(long need_id, long oppt_id, String oppt_nm, String job_cd,
            String grd_cd, String bgn_ym, String end_ym, int remaining_slots, int mtch_scr,
            ScoreComponents score_components, long waiting_days, BigDecimal win_prblt_rt,
            List<String> warnings) {}
    public record Temporary(long id, long need_id, long dev_id, String bgn_ym, String end_ym,
            BigDecimal mth_mm_qty, String tmpr_asgn_src_cd, BigDecimal mtch_scr,
            String status, Long asgn_id, List<String> warnings) {}
    public record TemporaryRow(long id, long need_id, long dev_id, String bgn_ym, String end_ym,
            BigDecimal mth_mm_qty, String tmpr_asgn_src_cd, BigDecimal mtch_scr,
            String status, Long asgn_id) {
        public Temporary view() {
            return new Temporary(id, need_id, dev_id, bgn_ym, end_ym, mth_mm_qty,
                tmpr_asgn_src_cd, mtch_scr, status, asgn_id, List.of());
        }
    }
    public record NextSlotsResult(List<NextSlot> items, int recommendation_count,
            long without_next_assignment_count, long with_valid_slots_count,
            List<Temporary> next_temp_assignments) {}
}
