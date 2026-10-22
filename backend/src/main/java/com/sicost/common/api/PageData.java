package com.sicost.common.api;

import java.util.List;

public record PageData<T>(List<T> items, long total, int page, int size) {
}
