package com.sicost.us4;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class PlanComparisonModels {
    private PlanComparisonModels() {}
    public record Query(int year, Long projectId, String job, String gap, String sort, int page, int size) {
        public LocalDate start() { return LocalDate.of(year, 1, 1); }
        public LocalDate end() { return LocalDate.of(year, 12, 31); }
        public String fromMonth() { return String.format("%04d-01", year); }
        public String toMonth() { return String.format("%04d-12", year); }
    }
    public record Source(long prj_id, String prj_no, String prj_nm, String job_cd,
                         String job_nm, int job_order, BigDecimal planMm, BigDecimal actualMm) {}
    public record Row(long prj_id, String prj_no, String prj_nm, String job_cd,
                      String job_nm, BigDecimal planMm, BigDecimal actualMm, BigDecimal gapMm, String gap) {}
    public record Totals(BigDecimal planMm, BigDecimal actualMm) {}
    public record Page(List<Row> rows, long total, int page, int size, Totals totals) {}
    public record ProjectOption(long prj_id, String prj_no, String prj_nm) {}
    public record Snapshot(Page data, LocalDate today, BigDecimal tolerance, List<ProjectOption> projects) {}
    public record ExportFile(String name, byte[] bytes) {}
}
