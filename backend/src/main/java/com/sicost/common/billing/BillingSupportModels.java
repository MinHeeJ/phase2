package com.sicost.common.billing;

import java.math.BigDecimal;
import java.time.*;

public final class BillingSupportModels {
    private BillingSupportModels() {}

    public record Period(String from, String to) {
        public Period {
            if (BillingRules.month(from, "from").isAfter(BillingRules.month(to, "to"))) {
                throw BillingRules.invalid("to");
            }
        }
        public LocalDate start() { return YearMonth.parse(from).atDay(1); }
        public LocalDate endExclusive() { return YearMonth.parse(to).plusMonths(1).atDay(1); }
    }
    public record ProjectMonth(long projectId, String month) {
        public ProjectMonth {
            if (projectId < 1) throw BillingRules.invalid("project_id");
            BillingRules.month(month, "month");
        }
    }
    public record Revenue(String month, long confirmed_supply_amt) {}
    public record CashReceipt(long receipt_id, long bill_id, LocalDate rcpt_dt, long rcpt_amt) {}
    public record Supply(long confirmed_supply_amt, long confirmed_count) {}
    public record Balance(long bill_id, String bill_stts_cd, String amnd_yn, long original_total_amt,
            long confirmed_adjustment_amt, long received_amt) {
        public long adjustedTotal() {
            return BillingRules.add(original_total_amt, confirmed_adjustment_amt, "sply_amt");
        }
        public long receivable() {
            if (!"CONFIRM".equals(bill_stts_cd) || !"N".equals(amnd_yn)) return 0;
            return Math.max(0, BillingRules.subtract(adjustedTotal(), received_amt, "rcpt_amt"));
        }
    }
    public record State(long bill_id, long prj_id, Long orgnl_bill_id, String bill_stts_cd,
            String contract_type, String atrb_ym, LocalDate bill_dt, LocalDate bill_plan_dt,
            LocalDateTime created_at) {
        public YearMonth effectiveMonth() {
            return BillingRules.effectiveMonth(contract_type, atrb_ym, bill_dt, bill_plan_dt, created_at);
        }
    }
    public record Gap(long basis_supply_amt, long confirmed_supply_amt, long difference_amt,
            BigDecimal tolerance, String judgment, boolean attendance_missing) {}
    public record Audit(String table, String id, String type, String field,
            String before, String after, String reason, Long actor) {}
}
