package com.sicost.salespartsops;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class SalesPartLifecycleModels {
    private SalesPartLifecycleModels() {}
    public record StandardSave(String aply_bgn_ym, String ldr_nm, BigDecimal prft_dstrb_rt, long bsn_fee_amt) {}
    public record SalesPartOption(String id, String name, boolean hq) {}
    public record PartValueWrite(long id, String value, Long actor) {}
    public record StandardReplacement(long id, StandardSave input, String previousMonth, Long actor) {}
    public record SalesPartAudit(String tableName, String recordId, String type, String column,
        String before, String after, Long actor) {}
    public record SalesPartExportQuery(List<Long> ids, String month, LocalDate today) {}
    public record SalesPartAccess(String filename, String detail, Long actor) {}
    public record SalesPartFile(String filename, byte[] bytes) {}
}
