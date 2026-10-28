package com.sicost.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

public final class BillingRules {
    private BillingRules() {}

    public static ApiException invalid(String field) {
        return new ApiException(400, "VALIDATION_ERROR", "입력값을 확인하세요.", field);
    }

    public static String contractType(String type) {
        if (type == null || !Set.of("TK", "MO", "DP").contains(type)) {
            throw invalid("contract_type");
        }
        return type;
    }

    public static YearMonth month(String value, String field) {
        try { return CalendarValues.month(value, field); }
        catch (ApiException ex) { throw invalid(field); }
    }

    public static LocalDate date(String value, String field) {
        try { return CalendarValues.date(value, field); }
        catch (ApiException ex) { throw invalid(field); }
    }

    public static YearMonth revenueMonth(String type, String attributionMonth, LocalDate billDate) {
        contractType(type);
        if (!"TK".equals(type)) return month(attributionMonth, "atrb_ym");
        if (billDate == null) throw invalid("bill_dt");
        return month(YearMonth.from(billDate).toString(), "bill_dt");
    }

    public static String status(String value) {
        if (value == null || !Set.of("PLAN", "DRAFT", "CONFIRM", "CANCEL").contains(value)) {
            throw invalid("bill_stts_cd");
        }
        return value;
    }

    public static boolean confirmed(String value) { return "CONFIRM".equals(status(value)); }

    public static long confirmedSupply(String status, long supply) {
        return confirmed(status) ? supply : 0;
    }

    public static long plannedSupply(String status, long supply) {
        return "PLAN".equals(status(status)) ? supply : 0;
    }

    public static long draftSupply(String status, long supply) {
        return "DRAFT".equals(status(status)) ? supply : 0;
    }

    public static long won(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid(field);
        return value.longValue();
    }

    public static long add(long first, long second, String field) {
        try { return Math.addExact(first, second); }
        catch (ArithmeticException ex) { throw invalid(field); }
    }

    public static long total(long supply, long vat) { return add(supply, vat, "tot_amt"); }

    public static void amounts(long supply, long vat, boolean amendment, boolean confirmation) {
        if (amendment ? supply >= 0 : supply < 0 || (confirmation && supply == 0)) {
            throw invalid("sply_amt");
        }
        if (amendment ? vat > 0 : vat < 0) throw invalid("vat_amt");
        total(supply, vat);
    }

    public static long sourceAmount(BigDecimal quantity, long price) {
        if (quantity == null || quantity.signum() < 0 || price < 0) throw invalid("bill_amt");
        try {
            return quantity.multiply(BigDecimal.valueOf(price))
                .setScale(0, RoundingMode.FLOOR).longValueExact();
        } catch (ArithmeticException ex) { throw invalid("bill_amt"); }
    }

    public static BigDecimal attendanceMm(BigDecimal workDays, BigDecimal monthWorkDays) {
        if (workDays == null || workDays.signum() < 0) throw invalid("work_day_qty");
        if (monthWorkDays == null || monthWorkDays.signum() <= 0) throw invalid("mth_work_day_qty");
        try {
            return ProjectValues.decimal(workDays.divide(monthWorkDays, 2, RoundingMode.HALF_UP), 5, "mm_qty");
        } catch (ApiException ex) { throw invalid("mm_qty"); }
    }

    public static long vat(long supply, BigDecimal effectiveRate) {
        if (supply < 0) throw invalid("sply_amt");
        BigDecimal rate;
        try { rate = ProjectValues.percent(effectiveRate == null ? BigDecimal.TEN : effectiveRate, "VAT_RATE"); }
        catch (ApiException ex) { throw invalid("VAT_RATE"); }
        try {
            return BigDecimal.valueOf(supply).multiply(rate).movePointLeft(2)
                .setScale(0, RoundingMode.FLOOR).longValueExact();
        } catch (ArithmeticException ex) { throw invalid("vat_amt"); }
    }

    public static long receivable(String status, long originalTotal, long confirmedAmendmentTotal, long received) {
        if (!confirmed(status)) return 0;
        long adjusted = add(originalTotal, confirmedAmendmentTotal, "adjusted_total_amt");
        if (originalTotal < 0 || confirmedAmendmentTotal > 0 || adjusted < 0 || received < 0) {
            throw invalid("receivable_amt");
        }
        if (received > adjusted) {
            throw ApiException.conflict("REFUND_REQUIRED", "입금액보다 적게 감액할 수 없습니다.", "rcpt_amt");
        }
        return adjusted - received;
    }

    public static void receipt(long requested, long available) {
        if (requested <= 0) throw invalid("rcpt_amt");
        if (requested > available) {
            throw ApiException.conflict("OVER_RECEIPT", "미수 잔액을 초과한 입금입니다.", "rcpt_amt");
        }
    }

    public static void draft(String state) {
        if (!"DRAFT".equals(status(state))) throw stale();
    }

    public static void changed(int affectedRows) {
        if (affectedRows != 1) throw stale();
    }

    public static ApiException stale() {
        return ApiException.conflict("STALE_BILL", "청구 상태가 변경되었습니다. 다시 조회하세요.", "bill_id");
    }

    public static void openMonth(String month, String databaseStatus) {
        month(month, "month");
        if ("CLOSED".equals(databaseStatus)) {
            throw ApiException.conflict("CLOSED_MONTH", "마감된 달은 변경할 수 없습니다.", "month");
        }
    }
}
