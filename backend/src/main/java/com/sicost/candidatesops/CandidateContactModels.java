package com.sicost.candidatesops;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sicost.common.security.Sensitive;
import com.sicost.developers.DeveloperModels.DeveloperDetail;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class CandidateContactModels {
    private CandidateContactModels() {}
    public record CandidateRow(long cand_id, String cand_no, String cand_nm, String cand_se_cd,
            Long vndr_ptnr_id, String job_cd, String grd_cd, Long hope_untprc_amt, LocalDate avail_dt,
            String cntct_stts_cd, LocalDate last_cntct_dt, String source, @Sensitive String tel_no,
            String rmrk_desc, Long dev_id, String use_yn, LocalDateTime created_at) {}
    public record Contact(LocalDate cntct_dt, String cntct_stts_cd, String memo,
            Long user_id, LocalDateTime created_at) {}
    public record ContactInput(LocalDate cntct_dt, String cntct_stts_cd, String memo) {}
    public record ContactWrite(long id, ContactInput input, Long actor) {}
    public record CandidateDetail(@JsonUnwrapped CandidateRow row, List<String> skill,
            List<Contact> contacts, List<MatchingSlot> matching_slots) {}
    public record MatchingSlot(long need_id, long oppt_id, String oppt_nm, String job_cd,
            String bgn_ym, long monthly_price, long hope_untprc_amt, long team_expense,
            BigDecimal margin_rate, boolean estimated, List<String> warnings) {}
    public record SlotRow(long need_id, long oppt_id, String oppt_nm, String job_cd,
            String bgn_ym, Long monthly_price, Long hope_untprc_amt) {}
    public record ConversionWrite(long id, long developerId, Long actor) {}
    public record ConversionResult(CandidateDetail candidate, DeveloperDetail developer) {}
    public record CandidateAudit(String id, String column, String before, String after, Long actor) {}
    public record Shortage(String job_cd, String grd_cd, BigDecimal maximum_shortage_mm,
            int maximum_shortage_people, String first_shortage_month, String recommended_hiring_month,
            boolean review_immediately, long candidate_count) {}
    public record ShortagePage(List<Shortage> items, int page, int size, long total) {}
    public record CandidateCount(String job, String grade, List<LocalDate> monthEnds) {}
}
