package com.sicost.billings;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.validation.BillingRules;
import java.time.LocalDate;
import java.util.*;

public final class BillingInput {
    private BillingInput() {}
    public static JsonNode parse(JsonNode node, boolean create) {
        if (node == null || !node.isObject()) throw BillingRules.invalid(null);
        var allowed = new HashSet<>(Set.of("bill_dt", "sply_amt", "vat_amt", "rcpt_plan_dt",
            "txinv_no", "txinv_dt", "details"));
        allowed.addAll(create ? Set.of("project_id", "stage_id", "month", "additional", "bill_odr_no")
            : Set.of("rmrk_desc", "recalculate"));
        node.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw BillingRules.invalid(k); });
        if (create) positive(node, "project_id", true);
        if (node.has("stage_id")) positive(node, "stage_id", true);
        if (node.has("bill_odr_no")) {
            long order = positive(node, "bill_odr_no", true);
            if (order > Integer.MAX_VALUE) throw BillingRules.invalid("bill_odr_no");
        }
        for (String field : List.of("sply_amt", "vat_amt")) {
            if (node.has(field)) BillingRules.won(node.get(field), field);
        }
        for (String field : List.of("bill_dt", "rcpt_plan_dt", "txinv_dt")) date(node, field, null);
        for (String field : List.of("additional", "recalculate")) {
            if (node.has(field) && !node.get(field).isBoolean()) throw BillingRules.invalid(field);
        }
        text(node, "txinv_no", 50, null);
        text(node, "rmrk_desc", 500, null);
        if (node.hasNonNull("month")) BillingRules.month(text(node, "month", 7, null), "month");
        overtime(node);
        return node;
    }
    public static long positive(JsonNode node, String field, boolean required) {
        if (!required && !node.has(field)) return 0;
        long id = BillingRules.won(node.get(field), field);
        if (id < 1) throw BillingRules.invalid(field);
        return id;
    }
    public static LocalDate date(JsonNode node, String field, LocalDate fallback) {
        if (!node.has(field)) return fallback;
        if (node.get(field).isNull()) return null;
        if (!node.get(field).isTextual()) throw BillingRules.invalid(field);
        return BillingRules.date(node.get(field).textValue(), field);
    }
    public static String text(JsonNode node, String field, int max, String fallback) {
        if (!node.has(field)) return fallback;
        var value = node.get(field);
        if (value.isNull()) return null;
        if (!value.isTextual() || value.textValue().length() > max) throw BillingRules.invalid(field);
        return value.textValue();
    }
    public static long amount(JsonNode node, String field, long fallback) {
        return node.has(field) ? BillingRules.won(node.get(field), field) : fallback;
    }
    public static Map<Long, Long> overtime(JsonNode node) {
        Map<Long, Long> result = new LinkedHashMap<>();
        if (!node.has("details")) return result;
        if (!node.get("details").isArray()) throw BillingRules.invalid("details");
        for (var line : node.get("details")) {
            if (!line.isObject()) throw BillingRules.invalid("details");
            line.fieldNames().forEachRemaining(k -> {
                if (!Set.of("asgn_id", "ovtm_amt").contains(k)) throw BillingRules.invalid("details." + k);
            });
            long id = positive(line, "asgn_id", true);
            long amount = BillingRules.won(line.get("ovtm_amt"), "ovtm_amt");
            if (amount < 0 || result.put(id, amount) != null) throw BillingRules.invalid("details");
        }
        return result;
    }
}
