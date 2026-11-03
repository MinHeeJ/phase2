package com.sicost.candidates;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sicost.common.security.Sensitive;
import com.sicost.developers.DeveloperModels.DeveloperDetail;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class CandidateModels {
    private CandidateModels() {}
    public record CandidateSave(String cand_nm,String cand_se_cd,Long vndr_ptnr_id,String job_cd,String grd_cd,
        List<String> skill,long hope_untprc_amt,LocalDate avail_dt,String cntct_stts_cd,@Sensitive String tel_no,
        String source,String rmrk_desc) {}
    public record CandidateRow(long cand_id,String cand_no,String cand_nm,String cand_se_cd,Long vndr_ptnr_id,
        String job_cd,String grd_cd,Long hope_untprc_amt,LocalDate avail_dt,String cntct_stts_cd,@Sensitive String tel_no,
        String source,String rmrk_desc,String use_yn,Long dev_id,LocalDate last_cntct_dt,LocalDateTime created_at) {}
    public record CandidateDetail(@JsonUnwrapped CandidateRow candidate,List<String> skill,
        List<CandidateContact> contacts,List<CandidateMatchingSlot> matching_slots) {}
    public record CandidateContact(LocalDate cntct_dt,String cntct_stts_cd,String memo,Long user_id,LocalDateTime created_at) {}
    public record ContactInput(LocalDate cntct_dt,String cntct_stts_cd,String memo) {}
    public record CandidateMatchingSlot(long need_id,long oppt_id,String oppt_nm,String job_cd,String bgn_ym,
        long monthly_price,long hope_untprc_amt,long team_expense,BigDecimal margin_rate,boolean estimated,List<String> warnings) {}
    public record SlotSource(long need_id,long oppt_id,String oppt_nm,String job_cd,String bgn_ym,Long monthly_price,BigDecimal team_expense) {}
    public record CandidateKpi(long candidate_count,long interview_count,long available_by_next_month_count,long uncontacted_over_30_days_count) {}
    public record CandidatePage(List<CandidateDetail> items,int page,int size,long total,CandidateKpi kpi) {}
    public record CandidateConversionResult(CandidateDetail candidate,DeveloperDetail developer) {}
    public record CandidateWrite(long id,String no,CandidateSave input,Long actor) {}
    public record CandidateChild(long id,String code) {}
    public record ContactWrite(long id,ContactInput input,Long actor) {}
    public record Conversion(long id,long developerId,Long actor) {}
    public record CandidateAudit(String id,String type,String column,String before,String after,Long actor) {}
    public record Code(String group,String code) {}
}
