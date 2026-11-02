package com.sicost.entries;

import com.sicost.common.security.Sensitive;
import java.time.LocalDate;
import java.util.List;

public final class FinanceEntryModels {
    private FinanceEntryModels() {}
    public record EntryInput(String entry_ym, String fin_se_cd, String item_nm,
            @Sensitive long entry_amt, @Sensitive long vat_amt, LocalDate inout_dt,
            String rpt_yn, String inout_cmpl_yn, String remark, Long copy_entry_id, String auto_fill_yn) {}
    public record FinanceEntry(Long entry_id, String entry_ym, String fin_se_cd, String item_nm,
            @Sensitive long entry_amt, @Sensitive long vat_amt, LocalDate inout_dt,
            String rpt_yn, String inout_cmpl_yn, String remark, Long copy_entry_id,
            String auto_fill_yn, boolean already_copied) {}
    public record EntryList(List<FinanceEntry> items, int saved_count, int total,
            List<FinanceEntry> drafts, String month_status, @Sensitive long total_amount,
            @Sensitive long total_vat, List<EntryMonth> months) {}
    public record EntryMonth(String month, String status, long saved_count) {}
    public record EntryPreview(List<FinanceEntry> items) {}
    public record EntryWrite(long id, EntryInput input, Long actor) {}
    public record EntryRemoval(long id, Long actor) {}
    public record EntryAudit(String id, String type, String before, String after, Long actor) {}
}
