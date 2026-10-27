package com.sicost.assignmentsops;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class AssignmentOperationModels {
    private AssignmentOperationModels() {}
    public record Version(long asgn_id, LocalDateTime updated_at) {}
    public record BatchRequest(List<Long> ids, List<Version> versions, String value) {}
    public record BatchItem(long asgn_id, String status, String code, String message, LocalDateTime updated_at) {}
    public record BatchResult(List<BatchItem> items) {}
    public record ReplacementRequest(LocalDateTime updated_at, LocalDate aply_dt, long dev_id,
        String rsn_cd, String rsn_desc) {}
    public record Proposal(long prj_id, long dev_id, String job_cd, LocalDate bgn_dt, LocalDate end_dt,
        BigDecimal asgn_rt, Long bill_untprc_amt, String untprc_base_cd, String asgn_se_cd,
        Long cntrct_dev_id, String ext_psblty_cd, String rmrk_desc, String rsn_cd, String rsn_desc) {}
    public record Warning(String code, String message, String work_ym) {}
    public record Month(String work_ym, BigDecimal auto_mm_qty, BigDecimal mm_qty, String mnl_yn,
        BigDecimal person_month_mm_qty, String closed_yn) {}
    public record Rate(LocalDate aply_bgn_dt, Long bill_untprc_amt) {}
    public record CheckResult(List<Warning> warnings, List<Month> months) {}
    public record Project(long prj_id, String cntrct_ty_cd, String prj_stts_cd, String use_yn) {}
    public record Developer(long dev_id, String emp_se_cd, String use_yn) {}
    public record Row(long asgn_id, String asgn_no, long prj_id, String prj_nm, long dev_id,
        String dev_nm, String mng_no, String job_cd, LocalDate bgn_dt, LocalDate end_dt,
        LocalDate plan_end_dt, BigDecimal asgn_rt, String asgn_se_cd, String untprc_base_cd,
        String ext_psblty_cd, Long cntrct_dev_id, String cntrct_dev_nm, String asgn_stts_cd,
        Long rplc_asgn_id, Long bill_untprc_amt, BigDecimal current_month_mm_qty,
        BigDecimal person_month_mm_qty, BigDecimal year_mm_qty, LocalDateTime updated_at,
        @JsonIgnore String rmrk_desc) {}
    public record Detail(@JsonUnwrapped Row row, List<Warning> warnings, String rmrk_desc,
        int extend_count, List<Rate> rates, List<Month> months) {}
    public record Change(long asgn_chg_id, long asgn_id, String asgn_chg_ty_cd, LocalDate aply_dt,
        String bfr_val, String aft_val, Long new_asgn_id, String rsn_cd, String rsn_nm,
        String rsn_desc, String retro_yn, String fixed_yn, Long fix_trgt_chg_id,
        LocalDate fix_trgt_aply_dt, String fix_trgt_chg_ty_cd, Long chg_user_id,
        String chg_user_nm, LocalDateTime chg_at) {}
    public record ReplacementResult(Detail previous_assignment, Detail new_assignment, List<Change> changes) {}
    public record ReadKey(long id, LocalDate today, int year) {}
    public record PersonPeriod(long project, long developer, LocalDate start, LocalDate end, Long excluded) {}
    public record PersonMonth(long developer, String month) {}
    public record CodeKey(String group, String code) {}
    public record ChanceWrite(long id, String value, Long actor) {}
    public record EndWrite(long id, LocalDate end, Long actor) {}
    public record NewAssignment(long id, String number, Row original, ReplacementRequest request, Long actor) {}
    public record MonthWrite(long id, String month, BigDecimal automatic, BigDecimal quantity, String manual) {}
    public record RateWrite(long id, LocalDate date, Long amount, Long actor) {}
    public record HistoryWrite(long id, String type, LocalDate date, String before, String after,
        Long linked, String reason, String description, String retro, Long actor) {}
    public record ExportQuery(int year, Long projectId, String job, String warning, String q,
        String sort, LocalDate today, LocalDate first, LocalDate last) {}
    public record ExportFile(String name, byte[] bytes) {}
}
