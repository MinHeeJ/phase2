package com.sicost.opportunities;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class OpportunityModels {
    private OpportunityModels() {}
    public record NeedInput(Long need_id, String job_cd, String grd_cd, int psn_cnt,
            String bgn_ym, int mth_cnt, BigDecimal mth_mm_qty, Long expc_untprc_amt, String rmrk_desc) {}
    public record Save(String oppt_nm, Long cust_ptnr_id, Long bsn_part_id, Long owner_user_id,
            String bsn_stg_cd, BigDecimal win_prblt_rt, long expc_amt, String cntrct_ty_cd,
            String bgn_ym, int mth_cnt, String next_action, LocalDate next_action_dt,
            String rmrk_desc, List<NeedInput> needs, String amount_difference_reason) {}
    public record Row(long oppt_id, String oppt_no, String oppt_nm, Long cust_ptnr_id,
            Long bsn_part_id, Long owner_user_id, String bsn_stg_cd, BigDecimal win_prblt_rt,
            long expc_amt, String cntrct_ty_cd, String bgn_ym, int mth_cnt, String next_action,
            LocalDate next_action_dt, String rmrk_desc, String amount_difference_reason,
            String oppt_stts_cd, Long win_prj_id, LocalDateTime updated_at, LocalDateTime created_at,
            String approval_state, Long expc_sls_amt, Long expc_cst_amt, BigDecimal expc_mrgn_rt,
            BigDecimal aprv_mrgn_rt, String zero_cost_evidence, String zero_cost_validation) {}
    public record NeedRow(long need_id, String job_cd, String grd_cd, int psn_cnt, String bgn_ym,
            int mth_cnt, BigDecimal mth_mm_qty, long expc_untprc_amt, String rmrk_desc,
            long psn_mth_cst_amt, long min_untprc_amt, long trgt_untprc_amt) {
        public NeedInput input() { return new NeedInput(need_id,job_cd,grd_cd,psn_cnt,bgn_ym,mth_cnt,mth_mm_qty,expc_untprc_amt,rmrk_desc); }
    }
    public record Need(@JsonUnwrapped NeedRow row, List<String> warnings) {}
    public record Quote(long expc_sls_amt, long expc_cst_amt, BigDecimal expc_mrgn_rt,
            long minimum_amount, long amount_difference, BigDecimal amount_difference_rate,
            String cost_status, String approval_state, Long approved_quote_amount, List<String> warnings) {}
    public record Approval(String action, String opinion, Long user_id, LocalDateTime created_at, BigDecimal expc_mrgn_rt) {}
    public record Detail(String oppt_nm, Long cust_ptnr_id, Long bsn_part_id, Long owner_user_id,
            String bsn_stg_cd, BigDecimal win_prblt_rt, long expc_amt, String cntrct_ty_cd,
            String bgn_ym, int mth_cnt, String next_action, LocalDate next_action_dt, String rmrk_desc,
            List<Need> needs, String amount_difference_reason, long oppt_id, String oppt_no,
            String oppt_stts_cd, Quote quote, List<Approval> approval_history, Long win_prj_id,
            LocalDateTime updated_at, LocalDateTime created_at, boolean stale_warning, List<String> warnings) {}
    public record Kpi(long open_count, long expected_amount, long weighted_amount, long needed_people, long stale_count) {}
    public record Page(List<Detail> items, int page, int size, long total, Kpi kpi) {}
    public record CostSource(long dev_id, String job_cd, String grd_cd, long mth_cst_id,
            String cst_ym, BigDecimal amount) {}
    public record Criteria(String key, String month) {}
    public record Code(String group, String code) {}
    public record Write(long id, Save input, BigDecimal probability, Quote quote, boolean retainApproval, Long actor) {}
    public record NeedWrite(long id, Long needId, NeedInput input, long cost, long target, int sequence) {}
    public record History(String id, String type, String before, String after, Long actor) {}
    public record Request(long id, BigDecimal margin, long amount, Long actor) {}
    public record Calculation(List<NeedWrite> needs, long revenue, long cost, BigDecimal margin,
            boolean unknown, List<String> warnings, String sourceSignature) {}
}
