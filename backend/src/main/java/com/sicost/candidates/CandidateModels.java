package com.sicost.candidates;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class CandidateModels {
    private CandidateModels() {}
    public record CandidateWrite(String cand_nm, String cand_se_cd, Long vndr_ptnr_id,
            String job_cd, String grd_cd, List<String> skill, Long hope_untprc_amt,
            LocalDate avail_dt, String cntct_stts_cd, @Sensitive String tel_no,
            String source, String rmrk_desc) {
        public Map<String,Object> columns() {
            Map<String,Object> values = new LinkedHashMap<>();
            values.put("cand_nm",cand_nm); values.put("cand_se_cd",cand_se_cd);
            values.put("vndr_ptnr_id",vndr_ptnr_id); values.put("job_cd",job_cd);
            values.put("grd_cd",grd_cd); values.put("hope_untprc_amt",hope_untprc_amt);
            values.put("asgn_psbl_dt",avail_dt); values.put("cntct_stts_cd",cntct_stts_cd);
            values.put("tel_no",tel_no); values.put("cand_src_cd",source); values.put("memo_desc",rmrk_desc);
            return values;
        }
        @Override public String toString() { return "CandidateWrite[sensitive input]"; }
    }
    public record CandidateRow(long cand_id, String cand_no, String cand_nm, String cand_se_cd,
            Long vndr_ptnr_id, String job_cd, String grd_cd, Long hope_untprc_amt,
            LocalDate avail_dt, String cntct_stts_cd, @Sensitive String tel_no, String source,
            String rmrk_desc, String use_yn, Long dev_id, LocalDate last_cntct_dt, LocalDateTime created_at) {
        public CandidateWrite input(List<String> skills) {
            return new CandidateWrite(cand_nm,cand_se_cd,vndr_ptnr_id,job_cd,grd_cd,skills,
                hope_untprc_amt,avail_dt,cntct_stts_cd,tel_no,source,rmrk_desc);
        }
        @Override public String toString() { return "CandidateRow[id="+cand_id+"]"; }
    }
    public record CandidateDetail(@JsonUnwrapped CandidateRow row, List<String> skill,
            List<CandidateContact> contacts, List<CandidateMatchingSlot> matching_slots) {}
    public record CandidateContact(LocalDate cntct_dt, String cntct_stts_cd, String memo,
            Long user_id, LocalDateTime created_at) {}
    public record CandidateSlotSource(long need_id, long oppt_id, String oppt_nm, String job_cd,
            String bgn_ym, Long monthly_price, Long team_expense) {}
    public record CandidateMatchingSlot(long need_id, long oppt_id, String oppt_nm, String job_cd,
            String bgn_ym, long monthly_price, long hope_untprc_amt, long team_expense,
            BigDecimal margin_rate, boolean estimated, List<String> warnings) {}
    public record CandidateKpi(long candidate_count, long interview_count,
            long available_by_next_month_count, long uncontacted_over_30_days_count) {}
    public record CandidatePage(List<CandidateDetail> items, int page, int size, long total, CandidateKpi kpi) {}
    public record CandidateCode(String group, String code) {}
    public record CandidateChild(long id, String code) {}
    public record CandidateInsert(long id, String no, CandidateWrite input, Long actor) {}
    public record CandidateUpdate(long id, Map<String,Object> changes, Long actor) {}
    public record CandidateHistory(String id, String type, String column, String before, String after, Long actor) {}
}
