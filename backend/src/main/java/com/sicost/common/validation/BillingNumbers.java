package com.sicost.common.validation;

import java.time.YearMonth;
import java.util.Locale;

public final class BillingNumbers {
    private BillingNumbers() {}

    public static String prefix(YearMonth creationMonth) {
        if (creationMonth == null || creationMonth.getYear() < 1 || creationMonth.getYear() > 9999) {
            throw BillingValues.invalid("bill_no");
        }
        return String.format(Locale.ROOT, "B-%02d%02d-", creationMonth.getYear() % 100,
            creationMonth.getMonthValue());
    }

    // Caller captures Seoul creation month and reads numeric MAX under a monthly advisory transaction lock.
    public static String allocated(YearMonth creationMonth, long maximumSuffix) {
        if (maximumSuffix < 0 || maximumSuffix == Long.MAX_VALUE) throw BillingValues.invalid("bill_no");
        String number = prefix(creationMonth) + String.format(Locale.ROOT, "%02d", maximumSuffix + 1);
        if (number.length() > 20) throw BillingValues.invalid("bill_no");
        return number;
    }
}
