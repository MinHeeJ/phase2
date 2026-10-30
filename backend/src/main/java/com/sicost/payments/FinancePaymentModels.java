package com.sicost.payments;

import java.time.*;
import java.util.List;
import com.sicost.common.security.Sensitive;

public final class FinancePaymentModels {
    private FinancePaymentModels() {}
    public record PaymentInput(String pay_ym, Integer pay_odr_no, String pay_se_cd, String rcvr_se_cd,
            Long dev_id, Long ptnr_id, String recipient, long sply_amt, long vat_amt, long ddct_amt,
            LocalDate pay_plan_dt, String bank, String account, String dpstr_nm, String remark,
            LocalDateTime updated_at) {
        public long amount() { return Math.subtractExact(Math.addExact(sply_amt,vat_amt),ddct_amt); }
    }
    public record Payment(long pay_id, String pay_ym, Integer pay_odr_no, String pay_se_cd,
            String rcvr_se_cd, Long dev_id, Long ptnr_id, String recipient,
            @Sensitive long sply_amt, @Sensitive long vat_amt, @Sensitive long ddct_amt, @Sensitive long pay_amt,
            LocalDate pay_plan_dt, LocalDate pay_dt, String pay_stts_cd, String pay_src_cd,
            Long src_rec_id, Long cry_pay_id, String bank, @Sensitive String account, String dpstr_nm,
            String remark, LocalDateTime updated_at, long executed_amt) {
        public long remaining() { return Math.subtractExact(pay_amt,executed_amt); }
    }
    public record PaymentList(List<Payment> items, int total, List<PaymentSummary> summary,
            boolean closed, List<String> warnings, List<PaymentChoice> categories, List<PaymentChoice> banks) {}
    public record PaymentChoice(String code, String name) {}
    public record PaymentSummary(Integer pay_odr_no, int count, long planned_amount, long paid_amount, long remaining_amount) {}
    public record PaymentWrite(long id, PaymentInput input, String source, Long sourceId, Long parentId, Long actor) {}
    public record PaymentState(long id, String status, LocalDate date, Long actor) {}
    public record PaymentExecution(long id, long payId, LocalDate date, long amount, Long reversalId, String reason) {}
    public record PaymentAudit(long id, String type, String field, String before, String after, String reason) {}
    public record CompleteInput(LocalDate pay_dt, Long paid_amount, LocalDateTime updated_at) {}
}
