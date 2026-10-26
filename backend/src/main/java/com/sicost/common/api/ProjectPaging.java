package com.sicost.common.api;

import java.util.Set;

public record ProjectPaging(int page, int size, String sort) {
    private static final Set<String> COLUMNS = Set.of(
        "prj_no", "prj_nm", "custName", "cntrct_ty_cd", "cntrct_bgn_dt", "cur_end_dt",
        "cur_cntrct_amt", "assignmentCount", "partName", "prj_stts_cd");

    public ProjectPaging {
        new DeveloperPaging(page, size, sort);
        if (sort == null || sort.isBlank()) sort = "cntrct_bgn_dt,desc";
        String[] parts = sort.split(",", -1);
        if (parts.length != 2 || !COLUMNS.contains(parts[0]) || !Set.of("asc", "desc").contains(parts[1])) {
            throw ApiException.badRequest("정렬을 확인하세요.", "sort");
        }
    }

    public static ProjectPaging defaults() { return new ProjectPaging(0, 50, null); }
    public long offset() { return new DeveloperPaging(page, size, sort).toPageRequest().offset(); }
    public String order() {
        String[] parts = sort.split(",", -1);
        return "\"" + parts[0] + "\" " + parts[1] + " NULLS LAST, prj_id ASC";
    }
}
