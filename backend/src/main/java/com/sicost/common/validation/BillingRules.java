package com.sicost.common.validation;

import com.sicost.common.api.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

public final class BillingRules {
    private BillingRules() {}

    public enum Status { PLAN, DRAFT, CONFIRM, CANCEL }
    public record Period(String contractType, String attributionMonth, LocalDate billDate) {}

    public static void requireBillable(String contractType) {
        if (!"TK".equals(contractType) && !"MO".equals(contractType) && !"DP".equals(contractType)) {
            throw BillingValues.invalid("project_id");
        }
    }

    public static Status status(String value) {
        if (value == null) throw BillingValues.invalid("bill_stts_cd");
        try {
            return Status.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BillingValues.invalid("bill_stts_cd");
        }
    }

    public static YearMonth accountingMonth(Period period) {
        requireBillable(period.contractType());
        if ("TK".equals(period.contractType())) {
            if (period.billDate() == null) return null;
            return BillingValues.month(YearMonth.from(period.billDate()).toString(), "bill_dt");
        }
        return BillingValues.month(period.attributionMonth(), "month");
    }

    // closed contains exact CLOSED rows read from fin.mth_cls inside the writer's transaction.
    public static void requireOpen(YearMonth month, Set<YearMonth> closed, String field) {
        if (month == null) throw BillingValues.invalid(field);
        if (closed.contains(month)) {
            throw ApiException.conflict("CLOSED_MONTH", "마감된 달은 변경할 수 없습니다.", field);
        }
    }

    public static void requireBillOpen(Period period, Set<YearMonth> closed) {
        YearMonth month = accountingMonth(period);
        if (month != null) {
            requireOpen(month, closed, "TK".equals(period.contractType()) ? "bill_dt" : "month");
        }
    }

    public static void requireBillMoveOpen(Period before, Period after, Set<YearMonth> closed) {
        requireBillOpen(before, closed);
        requireBillOpen(after, closed);
    }

    public static void requireReceiptMoveOpen(LocalDate before, LocalDate after, Set<YearMonth> closed) {
        if (before != null) requireOpen(YearMonth.from(before), closed, "rcpt_dt");
        if (after != null) requireOpen(YearMonth.from(after), closed, "rcpt_dt");
        if (before == null && after == null) throw BillingValues.invalid("rcpt_dt");
    }

    public static boolean pastYear(int year, Clock clock) {
        return year < ProjectValues.today(clock).getYear();
    }

    // State and affected-row checks consume re-read/conditional-update results, never a client state.
    public static void requireDraft(String lockedStatus) {
        if (status(lockedStatus) != Status.DRAFT) throw stale();
    }

    public static void requireOriginalConfirmed(String lockedStatus, boolean amendment) {
        if (status(lockedStatus) != Status.CONFIRM || amendment) throw stale();
    }

    public static void requireCancellable(String lockedStatus, long receiptCount) {
        Status current = status(lockedStatus);
        if ((current != Status.DRAFT && current != Status.CONFIRM) || receiptCount != 0) throw stale();
    }

    public static void requireChanged(int affectedRows) {
        if (affectedRows != 1) throw stale();
    }

    public static ApiException stale() {
        return ApiException.conflict("STALE_BILL", "청구가 변경되었습니다. 다시 조회해 주세요.", null);
    }

    public static boolean confirmed(String value) {
        return status(value) == Status.CONFIRM;
    }

    public static long confirmedSupply(String value, long supply) {
        return confirmed(value) ? supply : 0;
    }

    public static long plannedSupply(String value, long supply) {
        return status(value) == Status.PLAN ? supply : 0;
    }

    public static long draftSupply(String value, long supply) {
        return status(value) == Status.DRAFT ? supply : 0;
    }
}
