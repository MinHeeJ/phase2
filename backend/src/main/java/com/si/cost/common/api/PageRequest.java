package com.si.cost.common.api;

import java.util.Set;

public record PageRequest(int page, int size, String sortColumn, boolean descending) {
    public static PageRequest of(Integer page, Integer size, String sort, String defaultColumn) {
        int p = page == null ? 0 : page;
        int s = size == null ? 50 : size;
        if (p < 0 || (long) p * s > Integer.MAX_VALUE) throw BusinessException.invalid("page");
        if (!Set.of(20, 50, 100).contains(s)) throw BusinessException.invalid("size");
        String[] parts = (sort == null ? defaultColumn + ",asc" : sort).split(",", -1);
        if (parts.length != 2 || !parts[0].equals(defaultColumn)
                || !(parts[1].equals("asc") || parts[1].equals("desc"))) {
            throw BusinessException.invalid("sort");
        }
        return new PageRequest(p, s, defaultColumn, parts[1].equals("desc"));
    }
    public int offset() { return page * size; }
}
