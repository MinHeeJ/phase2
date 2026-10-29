package com.sicost.common.billing;

import com.sicost.shared.SharedReadMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingAmountService {
    private final SharedReadMapper source;

    public BillingAmountService(SharedReadMapper source) { this.source = source; }

    public long lineAmount(long price, BigDecimal quantity, boolean free) {
        if (price < 0 || quantity == null || quantity.signum() < 0) throw BillingRules.invalid("details");
        if (free) return 0;
        return BillingRules.won(BigDecimal.valueOf(price).multiply(quantity)
            .setScale(0, RoundingMode.FLOOR), "sply_amt");
    }

    public long hourlyAmount(long price, BigDecimal regularHours, long overtimeAmount, boolean free) {
        if (overtimeAmount < 0) throw BillingRules.invalid("ovtm_amt");
        long regular = lineAmount(price, regularHours, free);
        return free ? 0 : BillingRules.add(regular, overtimeAmount, "sply_amt");
    }

    public BigDecimal attendanceMm(BigDecimal workDays, BigDecimal monthWorkDays) {
        BillingRules.quantity(workDays, 5, 1, "work_day_qty");
        BillingRules.quantity(monthWorkDays, 5, 1, "mth_work_day_qty");
        if (monthWorkDays.signum() == 0) throw BillingRules.invalid("mth_work_day_qty");
        return BillingRules.quantity(workDays.divide(monthWorkDays, 2, RoundingMode.HALF_UP),
            5, 2, "mm_qty");
    }

    @Transactional(readOnly = true)
    public long suggestedVat(long supply, String month) {
        BillingRules.month(month, "month");
        var parameter = source.effectiveParam("VAT_RATE", month);
        BigDecimal rate = parameter == null || parameter.numericValue() == null
            ? BigDecimal.TEN : parameter.numericValue();
        if (rate.signum() < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
            throw BillingRules.invalid("VAT_RATE");
        }
        return BillingRules.won(BigDecimal.valueOf(supply).multiply(rate).movePointLeft(2)
            .setScale(0, RoundingMode.FLOOR), "vat_amt");
    }

    public long total(long supply, long vat, boolean amendment) {
        if (amendment ? supply >= 0 || vat > 0 : supply < 0 || vat < 0) {
            throw BillingRules.invalid(supply < 0 || amendment && supply >= 0 ? "sply_amt" : "vat_amt");
        }
        return BillingRules.add(supply, vat, "vat_amt");
    }
}
