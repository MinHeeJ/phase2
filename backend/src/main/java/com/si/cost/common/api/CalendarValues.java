package com.si.cost.common.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

public final class CalendarValues {
    private CalendarValues() { }
    public static YearMonth month(String value, String field) {
        if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw BusinessException.invalid(field);
        try {
            var month = YearMonth.parse(value);
            if (month.getYear() < 1) throw BusinessException.invalid(field);
            return month;
        }
        catch (DateTimeParseException ex) { throw BusinessException.invalid(field); }
    }
    public static LocalDate date(String value, String field) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw BusinessException.invalid(field);
        try { return LocalDate.parse(value, DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)); }
        catch (DateTimeParseException ex) { throw BusinessException.invalid(field); }
    }
}
