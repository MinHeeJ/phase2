package com.sicost.common.validation;

import com.sicost.common.api.ApiException;
import com.sicost.common.api.DeveloperPaging;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;
import java.util.function.Supplier;

public final class BillingValues {
    private BillingValues() {}

    public static ApiException invalid(String field) {
        return new ApiException(400, "VALIDATION_ERROR", "입력값을 확인하세요.", field);
    }

    private static <T> T input(Supplier<T> parse) {
        try {
            return parse.get();
        } catch (ApiException ex) {
            if (ex.status() != 400) throw ex;
            throw new ApiException(400, "VALIDATION_ERROR", ex.getMessage(), ex.field());
        }
    }

    public static YearMonth month(String value, String field) {
        return input(() -> CalendarValues.month(value, field));
    }

    public static LocalDate date(String value, String field) {
        return input(() -> CalendarValues.date(value, field));
    }

    public static long money(BigDecimal value, long minimum, String field) {
        return input(() -> ProjectValues.money(value, minimum, field));
    }

    public static DeveloperPaging paging(Integer page, Integer size, String sort,
            Set<String> allowed, String fallback) {
        return input(() -> {
            var paging = new DeveloperPaging(page == null ? 0 : page, size == null ? 50 : size, sort);
            String selected = paging.toPageRequest().allowedSort(allowed, fallback);
            if (!allowed.contains(selected)) throw invalid("sort");
            return new DeveloperPaging(paging.page(), paging.size(), selected);
        });
    }
}
