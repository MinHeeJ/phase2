package com.sicost.parts;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class PartSettlementModels {
    private PartSettlementModels() {}
    public record Key(long partId, String month) {}
    public record Part(long bsn_part_id, String bsn_part_nm) {}
    public record Standard(long part_std_hist_id, String ldr_nm, BigDecimal prft_dstrb_rt, long bsn_fee_amt) {}
    public record Salary(long dev_id, String dev_nm, String emp_se_cd, long real_pay_amt,
        long slry_amt, long rtrpay_acml_amt, LocalDate first_start) {}
    public record Header(long stlm_id, long bsn_part_id, String stlm_ym, Long part_std_hist_id,
        BigDecimal prft_dstrb_rt, Long bsn_fee_amt, Integer psn_cnt, Long tot_sls_amt,
        Long tot_exp_amt, Long tot_diff_amt, Long tot_psn_prft_amt, long corp_card_amt,
        Long part_prft_amt, String stlm_stts_cd, String card_remark) {}
    public record Person(long dev_id, String dev_nm, String emp_se_cd, BigDecimal mm_qty,
        long mth_untprc_amt, @Sensitive Long slry_amt, long bsn_fee_amt, long rtrpay_amt,
        @Sensitive Long tot_exp_amt, @Sensitive Long diff_amt, @Sensitive Long prft_amt,
        String rmrk_desc) {}
    public record History(String stlm_prcs_cd, String rsn_desc, Long prcs_user_id, LocalDateTime prcs_at) {}
    public record Month(String month, String status, boolean closed, boolean provisional,
        Standard standard, long corp_card_amt, String card_remark, @Sensitive Long part_prft_amt,
        List<Person> people, List<String> warnings, List<History> history) {}
    public record Summary(Part part, Month settlement) {}
    public record Detail(Part part, String view, List<Month> months) {}
    public record Snapshot(Key key, Standard standard, List<Person> people, long card,
        long sales, long expenses, long difference, long profit, long partProfit, Long actor) {}
    public record DetailWrite(long stlmId, Person person) {}
    public record CardWrite(long stlmId, long amount, Long actor) {}
    public record CancelWrite(long stlmId, String reason, Long actor) {}
    public record Audit(long stlmId, String column, String before, String after, String reason, Long actor) {}
    public record Event(long stlmId, String type, String reason, Long actor) {}
}
