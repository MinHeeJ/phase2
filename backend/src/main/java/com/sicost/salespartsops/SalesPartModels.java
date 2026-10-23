package com.sicost.salespartsops;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class SalesPartModels {
    private SalesPartModels() {}
    public record Part(String bsn_part_id, String bsn_part_nm, String hq_yn, String use_yn,
                       int sort_seq, long peopleCount, long projectCount) {}
    public record Standard(String part_std_hist_id, String aply_bgn_ym, String aply_end_ym,
                           String ldr_nm, BigDecimal prft_dstrb_rt, long bsn_fee_amt, String status) {}
    public record Detail(String bsn_part_id, String bsn_part_nm, String hq_yn, String use_yn,
                         int sort_seq, String referenceMonth, Standard currentStandard,
                         List<Standard> scheduledChanges, long peopleCount, long projectCount,
                         List<Standard> standards, String lastClosedMonth, String firstAllowedMonth) {}
    public record Option(String id, String name, boolean hq) {}
    public record Read(Long id, LocalDate today, String month) {}
    public record StandardSave(String aply_bgn_ym, String ldr_nm, BigDecimal prft_dstrb_rt, long bsn_fee_amt) {}
    public record ValueWrite(Long id, String value, Long actor) {}
    public record StandardWrite(Long id, StandardSave input, Long actor) {}
    public record PeriodWrite(Long id, String start, String previous, String month) {}
    public record History(String table, String id, String type, String column, String before, String after, Long actor) {}
    public record Access(String filename, String detail, Long actor) {}
    public record ExportQuery(LocalDate today, String month, List<Long> ids, String order) {}
    public record ExportRow(String bsn_part_id, String bsn_part_nm, String ldr_nm,
                            BigDecimal prft_dstrb_rt, Long bsn_fee_amt, String aply_bgn_ym,
                            String scheduledChanges, long peopleCount, long projectCount, String use_yn, int sort_seq) {}
    public record Download(String filename, byte[] bytes) {}
}
