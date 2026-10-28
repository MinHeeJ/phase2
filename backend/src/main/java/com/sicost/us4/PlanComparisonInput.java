package com.sicost.us4;

import com.sicost.common.api.ApiException;
import java.util.Map;
import java.util.Set;
import static com.sicost.us4.PlanComparisonModels.*;

public final class PlanComparisonInput {
    private PlanComparisonInput() {}
    private static final Set<String> SORT_KEYS = Set.of("prj_nm", "job_cd", "planMm", "actualMm", "gapMm");
    public static Query query(Map<String, String> values, boolean export) {
        var allowed = export ? Set.of("year", "projectId", "job", "gap", "sort")
            : Set.of("year", "projectId", "job", "gap", "sort", "page", "size");
        values.keySet().forEach(key -> { if (!allowed.contains(key)) throw error(key); });
        int year = number(values.get("year"), "year", 1, 9999);
        Long project = null;
        if (values.containsKey("projectId")) {
            try {
                project = Long.valueOf(values.get("projectId"));
                if (project < 1) throw error("projectId");
            } catch (NumberFormatException ex) { throw error("projectId"); }
        }
        String job = values.get("job");
        if (job != null && !job.matches("[A-Za-z0-9_]{1,30}")) throw error("job");
        String gap = values.get("gap");
        if (gap != null && !Set.of("SHORT", "OVER", "MATCH").contains(gap)) throw error("gap");
        String sort = values.getOrDefault("sort", "gapMm,asc;prj_nm,asc;job_cd,asc");
        if (sort.isEmpty() || sort.length() > 200) throw error("sort");
        for (String part : sort.split(";", -1)) {
            var pair = part.split(",", -1);
            if (pair.length != 2 || !SORT_KEYS.contains(pair[0]) || !Set.of("asc", "desc").contains(pair[1]))
                throw error("sort");
        }
        int page = number(values.getOrDefault("page", "0"), "page", 0, Integer.MAX_VALUE);
        int size = number(values.getOrDefault("size", "50"), "size", 50, 50);
        return new Query(year, project, job, gap, sort, page, size);
    }
    private static int number(String value, String field, int min, int max) {
        try {
            if (value == null || !value.matches("[0-9]+")) throw error(field);
            int number = Integer.parseInt(value);
            if (number < min || number > max) throw error(field);
            return number;
        } catch (NumberFormatException ex) { throw error(field); }
    }
    static ApiException error(String field) { return ApiException.badRequest("조회 조건을 확인하세요.", field); }
}
