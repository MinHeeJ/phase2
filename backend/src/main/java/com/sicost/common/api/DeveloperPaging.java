package com.sicost.common.api;

import java.util.List;

public record DeveloperPaging(int page, int size, String sort) {
    public DeveloperPaging {
        if (page < 0 || page == Integer.MAX_VALUE) {
            throw ApiException.badRequest("페이지를 확인하세요.", "page");
        }
        new PageRequest(page + 1, size, sort);
    }

    public PageRequest toPageRequest() {
        return new PageRequest(page + 1, size, sort);
    }

    public <T> PageResult<T> result(List<T> items, long total) {
        return new PageResult<>(items, total, page, size);
    }
}
