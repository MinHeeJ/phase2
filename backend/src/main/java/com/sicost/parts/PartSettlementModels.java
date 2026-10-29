package com.sicost.parts;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class PartSettlementModels {
    private PartSettlementModels() {}
    public record Key(long partId, String month) {}
    public record Part(long bsn_part_id, String bsn_part_nm) {}
    public record Standard(long part_std_hist_id, String aply_bgn_ym, String aply_end_ym,
        String ldr_nm, BigDecimal prft_dstrb_rt, @Sensitive long bsn_fee_amt) {}
    public record Salary(long dev_id, String dev_nm, String emp_se_cd, String duty_se_cd,
        @Sensitive long slry_amt, @Sensitive long rtrpay_acml_amt, LocalDate first_start,
        String projects) {}
    public record Header(Long stlm_id, long bsn_part_id, String stlm_ym, Long part_std_hist_id,
        BigDecimal prft_dstrb_rt, @Sensitive Long bsn_fee_amt, Integer psn_cnt,
        Long tot_sls_amt, @Sensitive Long tot_exp_amt, @Sensitive Long tot_diff_amt,
        @Sensitive Long tot_psn_prft_amt, @Sensitive long corp_card_amt,
        @Sensitive Long part_prft_amt, String stlm_stts_cd, LocalDateTime cnfrm_at) {}
    public record Person(long dev_id, String dev_nm, String emp_se_cd, String projects,
        BigDecimal mm_qty, Long mth_untprc_amt, @Sensitive Long slry_amt,
        @Sensitive Long bsn_fee_amt, @Sensitive Long rtrpay_amt, @Sensitive Long tot_exp_amt,
        @Sensitive Long diff_amt, @Sensitive Long prft_amt, String rmrk_desc) {}
    public record History(long stlm_hist_id, String stlm_prcs_cd, String rsn_desc,
        Long prcs_user_id, LocalDateTime prcs_at) {}
    public record TrendMonth(String month, String stlm_stts_cd, boolean provisional) {}
    public record TrendRow(long dev_id, String dev_nm, String metric,
        @Sensitive List<BigDecimal> values, @Sensitive BigDecimal total) {}
    public record Detail(String month, String status, String referenceMonth, String unit,
        List<String> warnings, boolean provisional, boolean confirmation_allowed,
        String bsn_part_nm, String ldr_nm, Header settlement, String note, List<Person> items,
        List<History> history, List<Standard> standards, List<TrendMonth> months,
        List<TrendRow> trend) {}
    public record Row(long bsn_part_id, String bsn_part_nm, String ldr_nm,
        BigDecimal prft_dstrb_rt, Integer psn_cnt, @Sensitive Long tot_diff_amt,
        @Sensitive Long tot_psn_prft_amt, @Sensitive long corp_card_amt,
        @Sensitive Long part_prft_amt, String stlm_stts_cd, boolean provisional) {}
    public record Summary(int part_count, int confirmed_count, @Sensitive Long part_prft_amt) {}
    public record Page(String month, String status, String referenceMonth, String unit,
        List<String> warnings, List<Row> items, int total, int page, int size, Summary summary) {}
    public record Card(long corp_card_amt, String note, boolean notePresent) {}
    public record CardWrite(long id, long amount, Long actor) {}
    public record Confirmation(Header header, Long actor) {}
    public record DetailWrite(long id, Person person) {}
    public record Transition(long id, String reason, Long actor) {}
    public record HistoryWrite(long id, String process, String reason, Long actor) {}
    public record Audit(String id, String column, String before, String after, String reason, Long actor) {}
    public record Access(String filename, String description, Long actor) {}
    public record File(String filename, byte[] bytes) {}
}
