package com.sicost.billings;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.validation.BillingValues;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

public record BillingQuery(int year, String status, String type, Long customerId, String q,
        int size, long offset, String orderColumn, String direction, int page) {
    public static BillingQuery parse(Map<String, String> query, Clock clock) {
        try {
            int year = BillingValues.year(integer(query, "year"), clock);
            var paging = BillingPaging.of(integer(query, "page"), integer(query, "size"), query.get("sort"));
            var sort = paging.allowedSort(Set.of("bill_dt", "bill_no", "project_name", "customer_name",
                "contract_type", "atrb_ym", "sply_amt", "vat_amt", "tot_amt", "bill_stts_cd",
                "txinv_no", "rcpt_plan_dt", "received_amt", "receivable_amt"), "bill_dt,desc");
            String status = value(query, "status"), type = value(query, "type");
            if (status != null && !Set.of("PLAN", "DRAFT", "CONFIRM", "CANCEL").contains(status))
                throw BillingValues.invalid("status");
            if (type != null && !Set.of("TK", "MO", "DP").contains(type)) throw BillingValues.invalid("type");
            Long customer = value(query, "customerId") == null ? null : Long.valueOf(query.get("customerId"));
            if (customer != null && customer < 1) throw BillingValues.invalid("customerId");
            String q = value(query, "q");
            if (q != null && q.length() > 200) throw BillingValues.invalid("q");
            return new BillingQuery(year, status, type, customer, q, paging.size(), paging.offset(),
                sort.field().equals("bill_dt") ? "sort_date" : sort.field(),
                sort.descending() ? "DESC" : "ASC", paging.page());
        } catch (NumberFormatException ex) { throw BillingValues.invalid(null); }
    }
    private static Integer integer(Map<String, String> q, String key) {
        try { return value(q, key) == null ? null : Integer.valueOf(q.get(key)); }
        catch (NumberFormatException ex) { throw BillingValues.invalid(key); }
    }
    private static String value(Map<String, String> q, String key) {
        String value = q.get(key);
        return value == null || value.isBlank() ? null : value;
    }
}
