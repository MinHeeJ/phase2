package com.sicost.contractchanges;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class ProjectContractChangeModels {
    private ProjectContractChangeModels() {}
    public record ChangeInput(String cntrct_chg_ty_cd, String cntrct_chg_stts_cd,
        LocalDate chg_dt, Long amount, BigDecimal incdec_mm_qty, LocalDate aft_end_dt,
        BigDecimal free_mm_qty, String rsn_desc) {}
    public record ProjectState(long prj_id, String cntrct_ty_cd, String use_yn,
        LocalDate cntrct_bgn_dt, LocalDate cntrct_end_dt, LocalDate prfm_bgn_dt,
        LocalDate cur_end_dt, Long init_cntrct_amt, Long cur_cntrct_amt, BigDecimal cntrct_mm_qty) {}
    public record ChangeRow(long chgId, String cntrct_chg_ty_cd, String cntrct_chg_stts_cd,
        LocalDate chg_dt, Long bfr_cntrct_amt, Long aft_cntrct_amt, LocalDate bfr_end_dt,
        LocalDate aft_end_dt, BigDecimal incdec_mm_qty, BigDecimal free_mm_qty,
        String rsn_desc, String realoc_need_yn, Long cnfrm_user_id, LocalDateTime cnfrm_at) {}
    public record Changes(LocalDate cntrct_bgn_dt, Long init_cntrct_amt, List<ChangeRow> items) {}
    public record ChangeKey(long id, long chgId) {}
    public record ChangeWrite(long id, ChangeRow row, Long actor) {}
    public record CurrentWrite(long id, Long amount, LocalDate end, BigDecimal mm, Long actor) {}
    public record ChangeAudit(String id, String type, String before, String after, Long actor) {}
    public record Removal(long prj_id, long chgId) {}
}
