package com.sicost.billings;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class BillingModels {
    private BillingModels() {}
    public record Project(long prj_id, String contract_type, String settlement, String use_yn) {}
    public record Stage(long stage_id, long project_id, String name, long supply, LocalDate date) {}
    public record Scope(long projectId, Long stageId, String month) {}
    public record Query(Integer year, String status, String type, Long customerId, String q,
        int page, int size, long offset, String order, LocalDate today) {}
    public record Row(Long bill_id, Long bill_stg_id, boolean virtual_plan, String bill_no,
        long prj_id, String project_name, Long customer_id, String customer_name, String contract_type,
        String atrb_ym, LocalDate bill_dt, long sply_amt, long vat_amt, long tot_amt,
        String bill_stts_cd, String txinv_no, LocalDate txinv_dt, LocalDate rcpt_plan_dt,
        long received_amt, long adjusted_total_amt, long receivable_amt, Integer reminder_stage) {}
    public record State(long bill_id, String bill_no, long prj_id, Long bill_stg_id, int bill_odr_no,
        String atrb_ym, LocalDate bill_dt, long sply_amt, long vat_amt, String bill_stts_cd,
        String txinv_no, LocalDate txinv_dt, LocalDate rcpt_plan_dt, String rmrk_desc,
        String amnd_yn, Long orgnl_bill_id, LocalDate created_date) {}
    public record Line(Long asgn_id, Long dev_id, Long cntrct_dev_id, String display_name,
        String work_ym, BigDecimal mm_qty, BigDecimal source_mm_qty, Long untprc_amt,
        BigDecimal work_day_qty, BigDecimal work_tm_qty, BigDecimal ovtm_tm_qty,
        long ovtm_amt, long bill_amt, String rmrk_desc) {}
    public record Source(long asgn_id, long dev_id, Long cntrct_dev_id, String display_name,
        String kind, BigDecimal mm, Long price, Long attendance_id, BigDecimal days,
        BigDecimal hours, BigDecimal overtime, BigDecimal month_days) {}
    public record Receipt(long receipt_id, LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record History(String description, LocalDateTime changed_at, String user_id) {}
    public record Detail(Row billing, List<Line> details, List<Receipt> receipts,
        List<Row> amendments, Long original_bill_id, List<History> change_history, List<String> warnings) {}
    public record Page(List<Row> items, long total, int page, int size) {}
    public record Proposal(List<Line> details, long supply, long vat, List<String> warnings) {}
    public record DetailWrite(long id, Line line) {}
    public record Write(State row, Long actor) {}
    public record Audit(String id, String type, String column, String before, String after, Long actor) {}
    public record ReadKey(long id, LocalDate today) {}
}
