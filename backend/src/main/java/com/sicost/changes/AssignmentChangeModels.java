package com.sicost.changes;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class AssignmentChangeModels {
    private AssignmentChangeModels() {}
    public record ChangeInput(LocalDateTime updated_at, String asgn_chg_ty_cd, LocalDate aply_dt,
        String rsn_cd, String rsn_desc, LocalDate end_dt, String work_ym, Long bill_untprc_amt,
        String ext_psblty_cd, String asgn_se_cd, LocalDate bgn_dt, Long cntrct_dev_id) {}
    public record FixInput(LocalDateTime updated_at, String aft_val, String rsn_cd, String rsn_desc) {}
    public record ChangeRow(long asgn_chg_id, long asgn_id, String asgn_chg_ty_cd, LocalDate aply_dt,
        String bfr_val, String aft_val, Long new_asgn_id, String rsn_cd, String rsn_nm, String rsn_desc,
        String retro_yn, String fixed_yn, Long fix_trgt_chg_id, LocalDate fix_trgt_aply_dt,
        String fix_trgt_chg_ty_cd, Long chg_user_id, String chg_user_nm, LocalDateTime chg_at) {}
    public record Changes(List<ChangeRow> items, int total) {}
    public record ChangeFilter(long id, String type) {}
    public record ChangeKey(long id, long chgId) {}
    public record State(long asgn_id, long prj_id, long dev_id, LocalDate bgn_dt, LocalDate end_dt,
        BigDecimal asgn_rt, String asgn_se_cd, Long cntrct_dev_id, Long rplc_asgn_id,
        String ext_psblty_cd, String asgn_stts_cd, String cntrct_ty_cd, String use_yn,
        String prj_stts_cd, String emp_se_cd, LocalDate rsgn_dt) {}
    public record Header(long asgn_id, String asgn_no, long prj_id, String prj_nm, long dev_id,
        String dev_nm, String mng_no, String job_cd, LocalDate bgn_dt, LocalDate end_dt,
        LocalDate plan_end_dt, BigDecimal asgn_rt, String asgn_se_cd, String untprc_base_cd,
        String ext_psblty_cd, Long cntrct_dev_id, String cntrct_dev_nm, String asgn_stts_cd,
        Long rplc_asgn_id, Long bill_untprc_amt, BigDecimal current_month_mm_qty,
        BigDecimal person_month_mm_qty, BigDecimal year_mm_qty, LocalDateTime updated_at) {}
    public record RateRow(LocalDate aply_bgn_dt, Long bill_untprc_amt) {}
    public record MonthRow(String work_ym, BigDecimal auto_mm_qty, BigDecimal mm_qty,
        String mnl_yn, BigDecimal person_month_mm_qty, String closed_yn) {}
    public record Warning(String code, String message, String work_ym) {}
    public record Detail(@JsonUnwrapped Header header, String rmrk_desc, int extend_count,
        List<RateRow> rates, List<MonthRow> months, List<Warning> warnings) {}
    public record SaveResult(Detail assignment, ChangeRow change) {}
    public record FixResult(Detail assignment, ChangeRow fix, ChangeRow target) {}
    public record ReadKey(long id, LocalDate today, String month, String year) {}
    public record PeriodWrite(long id, LocalDate start, LocalDate end, String kind, Long contractPerson) {}
    public record ChanceWrite(long id, String value) {}
    public record RateWrite(long id, LocalDate effective, Long amount, String reason, Long actor) {}
    public record RateMove(long id, LocalDate before, LocalDate after) {}
    public record MonthWrite(long id, String month, BigDecimal automatic, BigDecimal quantity, String manual) {}
    public record HistoryWrite(long id, String type, LocalDate application, String before, String after,
        String reason, String memo, String retro, Long target, Long actor) {}
    public record CodeKey(String group, String code) {}
}
