package com.sicost.common.api;

import java.util.Set;

public record PageRequest(int page, int size, String sort) {
    public PageRequest {
        if (page < 1) throw ApiException.badRequest("페이지를 확인하세요.", "page");
        if (!Set.of(20, 50, 100).contains(size)) throw ApiException.badRequest("목록 건수를 확인하세요.", "size");
    }
    public long offset() { return ((long) page - 1) * size; }
    public String allowedSort(Set<String> allowed, String fallback) {
        if (sort == null || sort.isBlank()) return fallback;
        if (!allowed.contains(sort)) throw ApiException.badRequest("정렬을 확인하세요.", "sort");
        return sort;
    }
}
