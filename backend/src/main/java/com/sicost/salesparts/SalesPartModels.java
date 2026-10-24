package com.sicost.salesparts;

import java.math.BigDecimal;
import java.util.List;

public final class SalesPartModels {
    private SalesPartModels() {}
    public record SalesPartCreate(String bsn_part_nm, String aply_bgn_ym, String ldr_nm,
        BigDecimal prft_dstrb_rt, long bsn_fee_amt) {}
    public record PartRow(String bsn_part_id, String bsn_part_nm, String hq_yn, String use_yn,
        int sort_seq, long peopleCount, long projectCount) {}
    public record SalesPartStandard(String part_std_hist_id, String aply_bgn_ym, String aply_end_ym,
        String ldr_nm, BigDecimal prft_dstrb_rt, long bsn_fee_amt, String status) {}
    public record SalesPartListItem(String bsn_part_id, String bsn_part_nm, String hq_yn, String use_yn,
        int sort_seq, String referenceMonth, SalesPartStandard currentStandard,
        List<SalesPartStandard> scheduledChanges, long peopleCount, long projectCount) {}
    public record SalesPartDetail(String bsn_part_id, String bsn_part_nm, String hq_yn, String use_yn,
        int sort_seq, String referenceMonth, SalesPartStandard currentStandard,
        List<SalesPartStandard> scheduledChanges, long peopleCount, long projectCount,
        List<SalesPartStandard> standards, String lastClosedMonth, String firstAllowedMonth) {}
    public record PartWrite(SalesPartCreate input, Long actor) {}
    public record StandardWrite(long partId, SalesPartCreate input, Long actor) {}
    public record PartHistory(String tableName, String recordId, String after, Long actor) {}
}
