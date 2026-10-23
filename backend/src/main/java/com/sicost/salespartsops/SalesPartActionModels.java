package com.sicost.salespartsops;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class SalesPartActionModels {
    private SalesPartActionModels() {}
    public record Part(String bsn_part_id, String bsn_part_nm, String hq_yn, String use_yn, int sort_seq) {}
    public record Option(String id, String name, boolean hq) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Standard(String part_std_hist_id, String aply_bgn_ym, String aply_end_ym,
        String ldr_nm, BigDecimal prft_dstrb_rt, Long bsn_fee_amt, String status) {}
    public record StandardSave(String aply_bgn_ym, String ldr_nm, BigDecimal prft_dstrb_rt, Long bsn_fee_amt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(String bsn_part_id, String bsn_part_nm, String hq_yn, String use_yn, int sort_seq,
        String referenceMonth, Standard currentStandard, List<Standard> scheduledChanges, long peopleCount,
        long projectCount, List<Standard> standards, String lastClosedMonth, String firstAllowedMonth) {}
    public record Read(String id, String month, LocalDate today) {}
    public record PartWrite(String id, String value, Long actor) {}
    public record StandardWrite(String id, StandardSave input, Long actor) {}
    public record EndWrite(String id, String month) {}
    public record History(String table, String id, String type, String field, String before, String after, Long actor) {}
    public record Download(String filename, String detail, Long actor) {}
    public record File(String filename, byte[] bytes) {}
}
