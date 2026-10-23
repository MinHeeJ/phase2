package com.sicost.common.validation;

import com.sicost.common.api.ApiException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

public final class CalendarValues {
    private CalendarValues() {}
    public static YearMonth month(String value, String field) {
        if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])") || value.startsWith("0000")) {
            throw ApiException.badRequest("올바른 연월을 입력하세요.", field);
        }
        return YearMonth.parse(value);
    }
    public static LocalDate date(String value, String field) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000")) {
                throw new DateTimeParseException("date", "", 0);
            }
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest("올바른 날짜를 입력하세요.", field);
        }
    }
}
