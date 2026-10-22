package com.sicost;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

final class Input {
    private Input() {}
    static void fields(Map<String, Object> row, Set<String> allowed) {
        if (row == null) throw ApiException.invalid("body", "입력값을 확인하세요.");
        for (String key : row.keySet()) if (!allowed.contains(key)) throw ApiException.invalid(key, "변경할 수 없는 항목입니다.");
    }
    static String text(Map<String, Object> row, String field, int max, String requiredMessage) {
        Object raw = row.get(field);
        if (raw == null && requiredMessage == null) return null;
        if (!(raw instanceof String)) throw ApiException.invalid(field, requiredMessage == null ? "문자를 입력하세요." : requiredMessage);
        String value = (String) raw;
        if (requiredMessage != null && value.isBlank()) throw ApiException.invalid(field, requiredMessage);
        if (value.length() > max) throw ApiException.invalid(field, "최대 " + max + "자까지 입력하세요.");
        return value;
    }
    static String choice(Map<String, Object> row, String field, Set<String> allowed) {
        String value = text(row, field, 30, "항목을 선택하세요.");
        if (!allowed.contains(value)) throw ApiException.invalid(field, "항목을 확인하세요.");
        return value;
    }
    static String month(String value, String field) {
        try {
            if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException();
            YearMonth.parse(value);
            return value;
        } catch (RuntimeException e) { throw ApiException.invalid(field, "연월을 확인하세요."); }
    }
    static void date(Map<String, Object> row) {
        String value = text(row, "aply_bgn_dt", 10, null);
        if (value != null) {
            try { LocalDate.parse(value); } catch (RuntimeException e) { throw ApiException.invalid("aply_bgn_dt", "날짜를 확인하세요."); }
        }
    }
    static void key(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) throw ApiException.invalid(field, "식별자를 확인하세요.");
    }
}
