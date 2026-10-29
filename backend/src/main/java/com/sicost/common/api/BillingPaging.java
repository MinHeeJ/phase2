package com.sicost.common.api;

import com.sicost.common.validation.BillingValues;
import java.util.List;
import java.util.Set;

public record BillingPaging(int page, int size, String sort) {
    public BillingPaging {
        try {
            new DeveloperPaging(page, size, sort);
        } catch (ApiException ex) {
            throw BillingValues.invalid(ex.field());
        }
    }

    public static BillingPaging of(Integer page, Integer size, String sort) {
        return new BillingPaging(page == null ? 0 : page, size == null ? 50 : size, sort);
    }

    public long offset() {
        return new DeveloperPaging(page, size, sort).toPageRequest().offset();
    }

    // Each query supplies response field names, never SQL expressions, and appends its stable ID tie-breaker.
    public Sort allowedSort(Set<String> fields, String fallback) {
        String requested = sort == null || sort.isBlank() ? fallback : sort;
        if (requested == null) throw BillingValues.invalid("sort");
        String[] parts = requested.split(",", -1);
        if (parts.length != 2 || !fields.contains(parts[0])
                || !("asc".equals(parts[1]) || "desc".equals(parts[1]))) {
            throw BillingValues.invalid("sort");
        }
        return new Sort(parts[0], "desc".equals(parts[1]));
    }

    public <T> PageResult<T> result(List<T> items, long total) {
        return new DeveloperPaging(page, size, sort).result(items, total);
    }

    public record Sort(String field, boolean descending) {}
}
