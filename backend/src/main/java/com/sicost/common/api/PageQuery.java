package com.sicost.common.api;

import java.util.Map;
import java.util.Set;

public record PageQuery(int page, int size, String sort) {
    public PageQuery {
        if (page < 1 || page > Integer.MAX_VALUE / 100) {
            throw ApiException.invalid("page", "올바른 페이지를 입력하세요.");
        }
        if (!Set.of(20, 50, 100).contains(size)) {
            throw ApiException.invalid("size", "목록 건수는 20, 50, 100 중 선택하세요.");
        }
    }

    public static PageQuery of(Integer page, Integer size, String sort) {
        return new PageQuery(page == null ? 1 : page, size == null ? 50 : size, sort);
    }

    public int offset() { return (page - 1) * size; }

    public String orderBy(Map<String, String> columns, String defaultOrder, String primaryKey) {
        if (sort == null || sort.isBlank()) {
            return defaultOrder + ", " + primaryKey;
        }
        String[] parts = sort.split(",", -1);
        if (parts.length != 2 || !columns.containsKey(parts[0])
                || !(parts[1].equals("asc") || parts[1].equals("desc"))) {
            throw ApiException.invalid("sort", "올바른 정렬을 선택하세요.");
        }
        return columns.get(parts[0]) + (parts[1].equals("asc") ? " ASC, " : " DESC, ") + primaryKey;
    }
}
