package com.sicost.common.api;

import com.sicost.common.validation.BillingRules;
import java.util.Set;

public record BillingPaging(int page, int size, String sort, View view) {
    public enum View {
        BILLINGS, VIRTUAL_PLANS, GAPS, OVERDUE
    }
    private static final Set<String> BILL_COLUMNS = Set.of(
        "bill_id", "bill_stg_id", "virtual_plan", "bill_no", "prj_id", "project_name",
        "customer_id", "customer_name", "contract_type", "atrb_ym", "bill_dt", "sply_amt",
        "vat_amt", "tot_amt", "bill_stts_cd", "txinv_no", "txinv_dt", "rcpt_plan_dt",
        "received_amt", "adjusted_total_amt", "receivable_amt", "reminder_stage");
    private static final Set<String> GAP_COLUMNS = Set.of(
        "project_id", "project_name", "month", "basis_supply_amt", "confirmed_supply_amt",
        "difference_amt", "tolerance", "judgment", "explanation", "attendance_missing");
    private static final Set<String> OVERDUE_COLUMNS = Set.of(
        "overdue_days", "stage", "due_status", "next_notification_date", "sales_part");

    public BillingPaging {
        if (page < 0) throw BillingRules.invalid("page");
        if (!Set.of(20, 50, 100).contains(size)) throw BillingRules.invalid("size");
        if (view == null) throw new IllegalArgumentException("조회 종류가 필요합니다.");
        if (sort == null) sort = defaultSort(view);
        String[] parts = sort.split(",", -1);
        if (parts.length != 2 || !Set.of("asc", "desc").contains(parts[1]) || !allowed(view, parts[0])) {
            throw BillingRules.invalid("sort");
        }
    }

    public static BillingPaging defaults(View view) { return new BillingPaging(0, 50, null, view); }
    public long offset() { return (long) page * size; }

    public String order() {
        String[] parts = sort.split(",", -1);
        String identity = switch (view) {
            case BILLINGS, OVERDUE -> "bill_id";
            case VIRTUAL_PLANS -> "bill_stg_id";
            case GAPS -> "project_id";
        };
        String order = "\"" + parts[0] + "\" " + parts[1] + " NULLS LAST";
        if (parts[0].equals(identity)) return order;
        return order + ", \"" + identity + "\" " + (view == View.BILLINGS ? "desc" : "asc");
    }

    private static String defaultSort(View view) {
        return switch (view) {
            case BILLINGS -> "bill_dt,desc";
            case VIRTUAL_PLANS -> "bill_plan_dt,asc";
            case GAPS -> "project_name,asc";
            case OVERDUE -> "overdue_days,desc";
        };
    }

    private static boolean allowed(View view, String field) {
        return switch (view) {
            case BILLINGS -> BILL_COLUMNS.contains(field);
            case VIRTUAL_PLANS -> BILL_COLUMNS.contains(field) || field.equals("bill_plan_dt");
            case GAPS -> GAP_COLUMNS.contains(field);
            case OVERDUE -> BILL_COLUMNS.contains(field) || OVERDUE_COLUMNS.contains(field);
        };
    }
}
