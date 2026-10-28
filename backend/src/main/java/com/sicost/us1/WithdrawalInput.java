package com.sicost.us1;

import com.sicost.common.api.ApiException;
import java.util.*;
import static com.sicost.us1.WithdrawalModels.*;

public final class WithdrawalInput {
    private WithdrawalInput() {}
    public static ApiException error(String field) {
        return ApiException.badRequest("조회 조건을 확인하세요.", field);
    }
    public static Query parse(Map<String, String> values, boolean export) {
        Set<String> allowed = new HashSet<>(Set.of("year", "period", "ext", "job", "q", "sort", "dispatchWithin60"));
        if (!export) allowed.addAll(Set.of("page", "size"));
        values.keySet().forEach(k -> { if (!allowed.contains(k)) throw error(k); });
        int year = integer(values.get("year"), "year", 1, 9999);
        int page = integer(values.getOrDefault("page", "0"), "page", 0, Integer.MAX_VALUE);
        int size = integer(values.getOrDefault("size", "50"), "size", 50, 50);
        String period = option(values, "period", Set.of("SOON", "LATER", "DONE"));
        String ext = option(values, "ext", Set.of("H", "M", "L", "NONE"));
        String job = text(values, "job", 30);
        if (job != null && !job.matches("[A-Z0-9_]{1,30}")) throw error("job");
        String dispatch = values.getOrDefault("dispatchWithin60", "false");
        if (!Set.of("true", "false").contains(dispatch)) throw error("dispatchWithin60");
        String sort = values.getOrDefault("sort", "end_dt,asc;dev_nm,asc");
        if (sort.length() > 300 || sort.isBlank()) throw error("sort");
        Set<String> fields = Set.of("asgn_id", "asgn_no", "dev_nm", "prj_nm", "job_cd", "end_dt", "remaining_days", "ext_psblty_cd");
        for (String part : sort.split(";", -1)) {
            String[] pair = part.split(",", -1);
            if (pair.length != 2 || !fields.contains(pair[0]) || !Set.of("asc", "desc").contains(pair[1])) throw error("sort");
        }
        return new Query(year, period, ext, job, text(values, "q", 200),
            Boolean.parseBoolean(dispatch), page, size, sort);
    }
    private static int integer(String value, String field, int min, int max) {
        try {
            if (value == null || !value.matches("[0-9]+")) throw error(field);
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw error(field);
            return parsed;
        } catch (NumberFormatException e) { throw error(field); }
    }
    private static String text(Map<String, String> values, String key, int max) {
        String value = values.get(key);
        if (value == null || value.isBlank()) return null;
        if (value.length() > max) throw error(key);
        return value.trim();
    }
    private static String option(Map<String, String> values, String key, Set<String> options) {
        String value = text(values, key, 30);
        if (value != null && !options.contains(value)) throw error(key);
        return value;
    }
}
