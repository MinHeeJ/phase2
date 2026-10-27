package com.sicost.apiops;

import com.sicost.common.api.*;
import java.util.*;

public record ProjectBoardInput(String light, String type, Long part, int page, int size, String sort) {
    public static ProjectBoardInput of(Map<String, String> input) {
        String light = choice(input.get("light"), "light", Set.of("위험", "주의", "정상"));
        String type = choice(input.get("type"), "type", Set.of("TK", "MO", "DP", "IN"));
        Long part = null;
        String raw = input.get("part");
        if (raw != null && !raw.isBlank()) {
            try { if (!raw.matches("[0-9]+") || (part = Long.valueOf(raw)) < 1) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw ApiException.badRequest("영업 파트를 확인하세요.", "part"); }
        }
        int page = integer(input, "page", 0), size = integer(input, "size", 50);
        new DeveloperPaging(page, size, input.get("sort"));
        String sort = input.get("sort");
        if (sort != null && !sort.isBlank()) {
            String[] split = sort.split(",", -1);
            if (split.length != 2 || !Set.of("asc", "desc").contains(split[1]) || !SORTABLE.contains(split[0])) {
                throw ApiException.badRequest("정렬을 확인하세요.", "sort");
            }
        } else sort = null;
        return new ProjectBoardInput(light, type, part, page, size, sort);
    }
    private static final Set<String> SORTABLE = Set.of("light", "reasons", "prj_no", "prj_nm", "custName", "pmName",
        "cntrct_ty_cd", "currentPhase", "plan_prgs_rt", "actl_prgs_rt", "schedule_diff_rt", "cost_use_rt",
        "cost_progress_diff_rt", "open_issue_count", "overdue_issue_count", "memo_elapsed_days", "last_memo_dt");
    private static int integer(Map<String, String> input, String key, int fallback) {
        if (!input.containsKey(key)) return fallback;
        try { return Integer.parseInt(input.get(key)); }
        catch (NumberFormatException ex) { throw ApiException.badRequest("조회 조건을 확인하세요.", key); }
    }
    private static String choice(String value, String field, Set<String> allowed) {
        if (value == null || value.isBlank()) return null;
        if (!allowed.contains(value)) throw ApiException.badRequest("조회 조건을 확인하세요.", field);
        return value;
    }
}
