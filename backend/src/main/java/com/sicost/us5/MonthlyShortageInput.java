package com.sicost.us5;

import com.sicost.common.api.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import static com.sicost.us5.MonthlyShortageModels.*;

public final class MonthlyShortageInput {
    private MonthlyShortageInput() {}
    public static Query query(Map<String, String> values, Clock clock, boolean export) {
        var allowed = export ? Set.of("year", "projectId", "job", "sort")
            : Set.of("year", "projectId", "job", "sort", "page", "size");
        values.keySet().forEach(key -> { if (!allowed.contains(key)) throw error(key); });
        int year = integer(values, "year", LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).getYear());
        if (year < 1 || year > 9999) throw error("year");
        Long project = null;
        if (values.containsKey("projectId")) {
            try { project = Long.valueOf(values.get("projectId")); }
            catch (NumberFormatException ex) { throw error("projectId"); }
            if (project < 1) throw error("projectId");
        }
        String job = values.get("job");
        if (job != null && (!job.matches("[A-Z0-9_]{1,30}"))) throw error("job");
        int page = integer(values, "page", 0), size = integer(values, "size", 50);
        if (page < 0) throw error("page");
        if (size != 50) throw error("size");
        String sort = values.getOrDefault("sort", "shortageMm,desc;plan_ym,asc");
        if (!sort.equals("shortageMm,desc;plan_ym,asc")) throw error("sort");
        return new Query(year, project, job, page, size, sort);
    }
    private static int integer(Map<String, String> values, String key, int fallback) {
        try { return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback; }
        catch (NumberFormatException ex) { throw error(key); }
    }
    public static ApiException error(String field) { return ApiException.badRequest("조회 조건을 확인하세요.", field); }
}
