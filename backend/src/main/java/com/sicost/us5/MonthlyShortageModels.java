package com.sicost.us5;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class MonthlyShortageModels {
    private MonthlyShortageModels() {}
    public record Query(int year, Long projectId, String job, int page, int size, String sort) {
        public String firstMonth() { return String.format("%04d-01", year); }
        public String lastMonth() { return String.format("%04d-12", year); }
        public LocalDate firstDay() { return LocalDate.of(year, 1, 1); }
        public LocalDate lastDay() { return LocalDate.of(year, 12, 31); }
    }
    public record Row(long prj_id, String prj_nm, String job_cd, String job_nm, String plan_ym,
                      BigDecimal planMm, BigDecimal actualMm, BigDecimal shortageMm) {}
    public record Page(List<Row> rows, long total, int page, int size) {}
    public record ExportFile(String name, byte[] bytes) {}
}
