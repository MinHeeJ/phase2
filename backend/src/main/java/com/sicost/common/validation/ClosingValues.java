package com.sicost.common.validation;

import com.sicost.common.api.ApiException;
import java.time.Clock;
import java.time.YearMonth;

public final class ClosingValues {
    private ClosingValues() {}

    public static YearMonth month(String value, String field) {
        try {
            return CalendarValues.month(value, field);
        } catch (ApiException ex) {
            throw new ApiException(400, "INVALID_MONTH", "올바른 연월을 입력하세요.", field);
        }
    }

    public static YearMonth currentMonth(Clock clock) {
        return YearMonth.from(ProjectValues.today(clock));
    }

    public static YearMonth confirmMonth(String value, Clock clock) {
        YearMonth selected = month(value, "month");
        if (selected.isAfter(currentMonth(clock))) {
            throw new ApiException(400, "INVALID_MONTH", "미래 월은 마감할 수 없습니다.", "month");
        }
        return selected;
    }

    public static YearMonth applyMonth(String value, Clock clock) {
        YearMonth selected = month(value, "applyMonth");
        if (!selected.equals(currentMonth(clock))) {
            throw ApiException.conflict("CURRENT_MONTH_REQUIRED", "현재 월에만 조정을 반영할 수 있습니다.", "applyMonth");
        }
        return selected;
    }
}
