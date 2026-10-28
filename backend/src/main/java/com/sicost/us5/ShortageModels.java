package com.sicost.us5;

import java.math.BigDecimal;
import java.util.List;

public final class ShortageModels {
    private ShortageModels() {}
    public record Filter(int year, Long projectId, String job, int page, int size) {}
    public record Row(long projectId, String projectName, String job, String jobName, String month,
                      BigDecimal planMm, BigDecimal actualMm, BigDecimal shortageMm) {}
    public record Page(List<Row> rows, int total, int page, int size) {}
}
