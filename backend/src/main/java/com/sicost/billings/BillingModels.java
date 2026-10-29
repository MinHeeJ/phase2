package com.sicost.billings;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;

public final class BillingModels {
    private BillingModels() {}
    public record BillingRow(Long bill_id, Long bill_stg_id, boolean virtual_plan, String bill_no,
        long prj_id, String project_name, Long customer_id, String customer_name, String contract_type,
        String atrb_ym, LocalDate bill_dt, long sply_amt, long vat_amt, long tot_amt,
        String bill_stts_cd, String txinv_no, LocalDate txinv_dt, LocalDate rcpt_plan_dt,
        long received_amt, long adjusted_total_amt, long receivable_amt, Integer reminder_stage) {}
    public record BillingPage(List<BillingRow> items, long total, int page, int size) {}
    public record DetailLine(Long asgn_id, Long dev_id, Long cntrct_dev_id, String display_name,
        String work_ym, BigDecimal mm_qty, BigDecimal source_mm_qty, Long untprc_amt,
        BigDecimal work_day_qty, BigDecimal work_tm_qty, BigDecimal ovtm_tm_qty, long ovtm_amt,
        long bill_amt, String rmrk_desc) {}
    public record Receipt(long receipt_id, LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record History(String description, LocalDateTime changed_at, String user_id) {}
    public record BillingDetail(BillingRow billing, List<DetailLine> details, List<Receipt> receipts,
        List<BillingRow> amendments, Long original_bill_id, List<History> change_history,
        List<String> warnings) {}
    public record Project(long prj_id, String contract_type, String stlm_mthd_cd, String use_yn) {}
    public record Stage(long bill_stg_id, long prj_id, int stg_odr_no, String bill_stg_nm,
        long sply_amt, LocalDate bill_plan_dt, LocalDateTime created_at) {}
    public record Header(long bill_id, long prj_id, Long bill_stg_id, int bill_odr_no, String atrb_ym,
        LocalDate bill_dt, long sply_amt, long vat_amt, String txinv_no, LocalDate txinv_dt,
        LocalDate rcpt_plan_dt, String rmrk_desc, String amnd_yn, Long orgnl_bill_id) {}
    public record Input(Map<String, Object> values, List<Overtime> details, boolean recalculate) {
        public boolean has(String key) { return values.containsKey(key); }
        public Object value(String key) { return values.get(key); }
        public Long number(String key) { return (Long) values.get(key); }
        public LocalDate date(String key) { return (LocalDate) values.get(key); }
        public String text(String key) { return (String) values.get(key); }
    }
    public record Overtime(long asgn_id, long ovtm_amt) {}
    public record Write(String number, Header header, Long actor) {}
    public record DetailWrite(long billId, DetailLine line) {}
    public record Duplicate(long projectId, Long stageId, String month) {}
    public record SourceKey(long projectId, String month, LocalDate start, LocalDate end) {}
    public record Source(long asgn_id, long dev_id, long cntrct_dev_id, String display_name,
        String asgn_se_cd, BigDecimal mm_qty, Long untprc_amt, Long atnd_id,
        BigDecimal work_day_qty, BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty,
        BigDecimal mth_work_day_qty) {}
    public record Calculation(List<DetailLine> details, long supply, List<String> warnings) {}
    public record Query(int year, LocalDate start, LocalDate end, String status, String type,
        Long customerId, String q, int page, int size, long offset, String sort, String direction,
        LocalDate today) {}
}
