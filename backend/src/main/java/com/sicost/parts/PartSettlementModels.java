package com.sicost.parts;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class PartSettlementModels {
    private PartSettlementModels() {}
    public record Scope(long partId, String month) {}
    public record PersonMonth(long devId, String month) {}
    public record Part(long bsn_part_id, String bsn_part_nm) {}
    public record Standard(long part_std_hist_id, String aply_bgn_ym, String aply_end_ym,
            String ldr_nm, BigDecimal prft_dstrb_rt, long bsn_fee_amt) {}
    public record Person(long dev_id, String dev_nm, String emp_se_cd,
            @Sensitive long real_pay_amt, @Sensitive long rtrpay_acml_amt, LocalDate first_start) {}
    public record Settlement(Long stlm_id, long bsn_part_id, String stlm_ym, String stlm_stts_cd,
            Long part_std_hist_id, String ldr_nm, BigDecimal prft_dstrb_rt, Long bsn_fee_amt,
            int psn_cnt, long tot_sls_amt, @Sensitive long tot_exp_amt, long tot_diff_amt,
            @Sensitive long tot_psn_prft_amt, long corp_card_amt, @Sensitive long part_prft_amt,
            String rmrk_desc, LocalDateTime cnfrm_at) {}
    public record Item(Long stlm_dtl_id, long dev_id, String dev_nm, String emp_se_cd,
            String project_names, BigDecimal mm_qty, long mth_untprc_amt, @Sensitive long slry_amt,
            long bsn_fee_amt, long rtrpay_amt, @Sensitive long tot_exp_amt, long diff_amt,
            @Sensitive long prft_amt, String rmrk_desc) {}
    public record History(long stlm_hist_id, String stlm_prcs_cd, String rsn_desc,
            Long prcs_user_id, LocalDateTime prcs_at) {}
    public record TrendValue(String month, BigDecimal value, boolean confirmed) {}
    public record Trend(long dev_id, String dev_nm, String metric, List<TrendValue> months) {}
    public record Detail(String calculation_month, String reference_month, boolean closed, String unit,
            List<String> warnings, Settlement settlement, List<Item> items, List<Trend> trend,
            List<History> history, List<Standard> standard_history, boolean provisional,
            boolean confirmation_available) {}
    public record PartRow(long bsn_part_id, String bsn_part_nm, Settlement settlement,
            boolean provisional, boolean confirmation_available, List<String> warnings) {}
    public record SettlementList(String calculation_month, String reference_month, boolean closed,
            String unit, List<String> warnings, List<PartRow> items) {}
    public record Card(long corp_card_amt, String rmrk_desc) {}
    public record CardWrite(Scope scope, Card card, Long actor) {}
    public record SnapshotWrite(long id, Settlement settlement, Long actor, LocalDateTime now) {}
    public record ItemWrite(long id, Item item) {}
    public record Transition(long id, String before, String after, String reason,
            Long actor, LocalDateTime now) {}
    public record Audit(long id, String column, String before, String after, String reason, Long actor) {}
    public record Access(String type, String object, String description, Long actor) {}
}
