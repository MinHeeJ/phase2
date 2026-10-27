package com.sicost.assignments;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class AssignmentModels {
    private AssignmentModels() {}
    public record CreateInput(Long prj_id, Long dev_id, String job_cd, LocalDate bgn_dt,
        LocalDate end_dt, BigDecimal asgn_rt, Long bill_untprc_amt, String untprc_base_cd,
        String asgn_se_cd, Long cntrct_dev_id, String ext_psblty_cd, String rmrk_desc,
        String rsn_cd, String rsn_desc) {}
    public record Query(int year, Long projectId, String job, String warning, String q,
        int page, int size, String sort, LocalDate today, String month, LocalDate from, LocalDate to) {}
    public record ReadKey(long id, int year, LocalDate today, String month) {}
    public record Project(long prj_id, String cntrct_ty_cd, String prj_stts_cd, String use_yn) {}
    public record Developer(long dev_id, String use_yn, LocalDate rsgn_dt) {}
    public record CodeKey(String group, String code) {}
    public record Row(long asgn_id, String asgn_no, long prj_id, String prj_nm, long dev_id,
        String dev_nm, String mng_no, String job_cd, LocalDate bgn_dt, LocalDate end_dt,
        LocalDate plan_end_dt, BigDecimal asgn_rt, String asgn_se_cd, String untprc_base_cd,
        String ext_psblty_cd, Long cntrct_dev_id, String cntrct_dev_nm, String asgn_stts_cd,
        Long rplc_asgn_id, Long bill_untprc_amt, BigDecimal current_month_mm_qty,
        BigDecimal person_month_mm_qty, BigDecimal year_mm_qty, LocalDateTime updated_at,
        @JsonIgnore String rmrk_desc, @JsonIgnore int extend_count,
        @JsonIgnore boolean over_mm, @JsonIgnore boolean pre_contract,
        @JsonIgnore boolean on_leave, @JsonIgnore boolean dispatch_nonreg) {}
    public record Warning(String code, String message, String work_ym) {}
    public record Item(@JsonUnwrapped Row row, List<Warning> warnings) {}
    public record Page(List<Item> items, long total, int page, int size) {}
    public record Rate(LocalDate aply_bgn_dt, Long bill_untprc_amt) {}
    public record Month(String work_ym, BigDecimal auto_mm_qty, BigDecimal mm_qty,
        String mnl_yn, BigDecimal person_month_mm_qty, String closed_yn) {}
    public record Detail(@JsonUnwrapped Item item, String rmrk_desc, int extend_count,
        List<Rate> rates, List<Month> months) {}
    public record Change(long asgn_chg_id, long asgn_id, String asgn_chg_ty_cd,
        LocalDate aply_dt, String bfr_val, String aft_val, Long new_asgn_id, String rsn_cd,
        String rsn_nm, String rsn_desc, String retro_yn, String fixed_yn, Long fix_trgt_chg_id,
        LocalDate fix_trgt_aply_dt, String fix_trgt_chg_ty_cd, Long chg_user_id,
        String chg_user_nm, LocalDateTime chg_at) {}
    public record SaveResult(Detail assignment, Change change) {}
    public record Write(long id, String number, CreateInput input, String basis, Long actor) {}
    public record RateWrite(long id, LocalDate date, Long amount, Long actor) {}
    public record MonthWrite(long id, String month, BigDecimal quantity, Long actor) {}
    public record StartWrite(long id, CreateInput input, String display, String retro, Long actor) {}
}
