package com.sicost.allocations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ProjectAllocationModels {
    private ProjectAllocationModels() {}

    public record AllocationMonth(String aloc_ym, Long aloc_amt, BigDecimal mm_qty) {}
    public record AllocationLine(String line_ty_cd, Long dev_id, String item_nm,
            String job_cd, LocalDate asgn_bgn_dt, String asgn_se_cd,
            String rmrk_desc, int sort_seq, List<AllocationMonth> months) {}
    public record Allocations(String aloc_stg_cd, String activeStage,
            List<AllocationLine> items, Long cur_cntrct_amt, String realoc_need_yn) {}
    public record AllocationProject(String cntrct_ty_cd, String use_yn,
            Long cur_cntrct_amt, LocalDate begin_date, LocalDate end_date) {}
    public record AllocationScope(long id, String stage) {}
    public record AllocationCode(String group, String code) {}
    public record AllocationStoredLine(long aloc_line_id, String line_ty_cd,
            Long dev_id, String item_nm, String job_cd, LocalDate asgn_bgn_dt,
            String asgn_se_cd, String rmrk_desc, int sort_seq) {
        public AllocationLine withMonths(List<AllocationMonth> months) {
            return new AllocationLine(line_ty_cd, dev_id, item_nm, job_cd,
                    asgn_bgn_dt, asgn_se_cd, rmrk_desc, sort_seq, months);
        }
    }
    public record AllocationWrite(AllocationScope scope, AllocationLine line, Long actor) {}
    public record AllocationMonthWrite(long id, AllocationMonth month) {}
    public record AllocationAudit(String id, String stage, String before, String after, Long actor) {}
}
