package com.sicost.common.validation;

import com.sicost.common.api.ApiException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Set;

public final class BillingRules {
    private BillingRules() {}

    public enum Status { PLAN, DRAFT, CONFIRM, CANCEL }

    public record Period(String contractType, YearMonth attributionMonth,
            LocalDate billDate, LocalDate stagePlannedDate) {
        public Period { requireBillable(contractType); }

        public YearMonth revenueMonth() {
            if (!"TK".equals(contractType)) return requiredMonth(attributionMonth, "atrb_ym");
            if (billDate == null) throw BillingValues.invalid("bill_dt");
            return YearMonth.from(billDate);
        }

        public YearMonth billingMonth() {
            if (!"TK".equals(contractType) || billDate != null) return revenueMonth();
            if (stagePlannedDate == null) throw BillingValues.invalid("bill_dt");
            return YearMonth.from(stagePlannedDate);
        }
    }

    private static YearMonth requiredMonth(YearMonth month, String field) {
        if (month == null || month.getYear() < 1 || month.getYear() > 9999) throw BillingValues.invalid(field);
        return month;
    }

    public static void requireBillable(String contractType) {
        if (contractType == null || !Set.of("TK", "MO", "DP").contains(contractType)) {
            throw BillingValues.invalid("contract_type");
        }
    }

    // status is read from fin.mth_cls inside the caller's command transaction; absent row is OPEN.
    public static void requireOpen(YearMonth month, String status, String field) {
        requiredMonth(month, field);
        if ("CLOSED".equals(status)) {
            throw ApiException.conflict("CLOSED_MONTH", month + "은 마감된 달이라 입력할 수 없습니다.", field);
        }
    }

    public static void requireOpenChange(Period before, Period after, Map<YearMonth, String> closeStates) {
        YearMonth original = before.billingMonth();
        YearMonth target = after.billingMonth();
        requireOpen(original, closeStates.get(original), "month");
        requireOpen(target, closeStates.get(target), "month");
    }

    public static void requireReceiptMonths(LocalDate original, LocalDate target,
            Map<YearMonth, String> closeStates) {
        if (original == null && target == null) throw BillingValues.invalid("rcpt_dt");
        if (original != null) {
            YearMonth month = YearMonth.from(original);
            requireOpen(month, closeStates.get(month), "rcpt_dt");
        }
        if (target != null) {
            YearMonth month = YearMonth.from(target);
            requireOpen(month, closeStates.get(month), "rcpt_dt");
        }
    }

    public static long confirmedSupply(Status status, long supply) {
        return status == Status.CONFIRM ? supply : 0;
    }

    public static long plannedSupply(Status status, long supply) {
        return status == Status.PLAN ? supply : 0;
    }

    public static long draftSupply(Status status, long supply) {
        return status == Status.DRAFT ? supply : 0;
    }

    // adjustedTotal already includes CONFIRM amendments; receipts are root receipts, VAT included.
    public static long receivable(Status status, long adjustedTotal, long received) {
        if (status != Status.CONFIRM || adjustedTotal <= received) return 0;
        if (received < 0) throw BillingValues.invalid("received_amt");
        return adjustedTotal - received;
    }

    public static void requireDraft(Status status) {
        if (status != Status.DRAFT) throw stale();
    }

    public static void requireConfirmation(Status status, LocalDate date, long supply,
            long vat, boolean amendment) {
        requireDraft(status);
        if (date == null) throw BillingValues.invalid("bill_dt");
        if (amendment ? supply >= 0 : supply <= 0) throw BillingValues.invalid("sply_amt");
        if (amendment ? vat > 0 : vat < 0) throw BillingValues.invalid("vat_amt");
        BillingValues.add(supply, vat, "tot_amt");
    }

    public static void requireChanged(int affectedRows) {
        if (affectedRows != 1) throw stale();
    }

    public static ApiException stale() {
        return ApiException.conflict("STALE_BILL", "청구 상태가 변경되었습니다. 다시 조회해 주세요.", "bill_id");
    }

    public static void requireReceiptTarget(Status status, boolean amendment) {
        if (status != Status.CONFIRM || amendment) throw stale();
    }

    // All balances must be re-read after locking the root bill FOR UPDATE. For edits exclude the old receipt.
    public static void requireReceiptAmount(long adjustedTotal, long receivedExcludingCurrent, long requested) {
        if (requested <= 0) throw BillingValues.invalid("rcpt_amt");
        if (receivedExcludingCurrent < 0) throw BillingValues.invalid("received_amt");
        boolean exceeds;
        try {
            exceeds = Math.addExact(receivedExcludingCurrent, requested) > adjustedTotal;
        } catch (ArithmeticException ex) {
            exceeds = true;
        }
        if (exceeds) {
            throw ApiException.conflict("OVER_RECEIPT", "입금액이 조정 후 청구 잔액을 초과합니다.", "rcpt_amt");
        }
    }

    // currentTotal includes only already-confirmed adjustments, excluding the candidate amendment itself.
    public static void requireAdjustedTotal(long currentTotal, long amendmentTotal, long received) {
        if (currentTotal < 0 || amendmentTotal >= 0 || received < 0) throw BillingValues.invalid("sply_amt");
        long adjusted = BillingValues.add(currentTotal, amendmentTotal, "tot_amt");
        if (adjusted < 0) throw BillingValues.invalid("sply_amt");
        if (adjusted < received) {
            throw ApiException.conflict("REFUND_REQUIRED", "입금액보다 적게 조정하려면 환급 처리가 필요합니다.", "sply_amt");
        }
    }
}
