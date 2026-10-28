package com.sicost.common.validation;

import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

public final class BillingAmounts {
    private BillingAmounts() {}

    public record Amounts(long supply, long vat) {
        public long total() {
            return exact(BigDecimal.valueOf(supply).add(BigDecimal.valueOf(vat)), "vat_amt");
        }
    }

    public static long exact(BigDecimal amount, String field) {
        return BillingValues.money(amount, Long.MIN_VALUE, field);
    }

    public static long suggestedVat(long supply, BigDecimal effectiveRate) {
        BigDecimal rate = effectiveRate == null ? BigDecimal.TEN : effectiveRate;
        if (rate.signum() < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
            throw BillingValues.invalid("vat_amt");
        }
        return exact(BigDecimal.valueOf(supply).multiply(rate)
            .divide(new BigDecimal("100"), 0, RoundingMode.FLOOR), "vat_amt");
    }

    public static void requireConfirmation(Amounts amounts, boolean amendment, LocalDate billDate) {
        if (billDate == null) throw BillingValues.invalid("bill_dt");
        BillingValues.date(billDate.toString(), "bill_dt");
        if (amendment ? amounts.supply() >= 0 : amounts.supply() <= 0) {
            throw BillingValues.invalid("sply_amt");
        }
        if (amendment ? amounts.vat() > 0 : amounts.vat() < 0) throw BillingValues.invalid("vat_amt");
        amounts.total();
    }

    public static long adjustedTotal(long originalTotal, long confirmedAmendmentTotal) {
        return exact(BigDecimal.valueOf(originalTotal).add(BigDecimal.valueOf(confirmedAmendmentTotal)),
            "sply_amt");
    }

    public static long receivable(String status, long adjustedTotal, long received) {
        if (!BillingRules.confirmed(status)) return 0;
        return exact(BigDecimal.valueOf(adjustedTotal).subtract(BigDecimal.valueOf(received))
            .max(BigDecimal.ZERO), "rcpt_amt");
    }

    // remainingReceipts excludes the edited row and is read after locking the original bill.
    public static void requireReceipt(long adjustedTotal, long remainingReceipts, long requested) {
        if (requested <= 0) throw BillingValues.invalid("rcpt_amt");
        if (remainingReceipts < 0) throw BillingValues.invalid("rcpt_amt");
        if (BigDecimal.valueOf(remainingReceipts).add(BigDecimal.valueOf(requested))
                .compareTo(BigDecimal.valueOf(adjustedTotal)) > 0) {
            throw ApiException.conflict("OVER_RECEIPT", "청구 잔액보다 많은 금액은 입금할 수 없습니다.", "rcpt_amt");
        }
    }

    public static void requireRefundSafe(long proposedAdjustedTotal, long received) {
        if (proposedAdjustedTotal < received) {
            throw ApiException.conflict("REFUND_REQUIRED", "입금액보다 적게 조정하려면 환불 확인이 필요합니다.", "sply_amt");
        }
    }
}
