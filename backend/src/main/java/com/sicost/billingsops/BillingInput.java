package com.sicost.billingsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.validation.BillingRules;
import java.time.LocalDate;
import java.util.*;
import static com.sicost.billingsops.BillingModels.*;

public final class BillingInput {
    private BillingInput() {}
    static JsonNode parse(ObjectReader reader, String body, Set<String> allowed) {
        try {
            JsonNode node = reader.readTree(body);
            if (node == null || !node.isObject()) throw BillingRules.invalid(null);
            node.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw BillingRules.invalid(k); });
            return node;
        } catch (JsonProcessingException ex) { throw BillingRules.invalid(null); }
    }
    static String text(JsonNode node, String key, int max, boolean required) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            if (required) throw BillingRules.invalid(key);
            return null;
        }
        if (!value.isTextual()) throw BillingRules.invalid(key);
        String result = value.textValue().trim();
        if (result.length() > max || (required && result.isEmpty())) throw BillingRules.invalid(key);
        return result.isEmpty() ? null : result;
    }
    static LocalDate date(JsonNode n, String key) {
        String value = text(n, key, 10, false);
        return value == null ? null : BillingRules.date(value, key);
    }
    static Amendment amendment(JsonNode n) {
        return new Amendment(BillingRules.won(n.get("sply_amt"), "sply_amt"),
            BillingRules.won(n.get("vat_amt"), "vat_amt"), text(n, "reason", 500, true),
            date(n, "bill_dt"), date(n, "rcpt_plan_dt"), text(n, "txinv_no", 50, false), date(n, "txinv_dt"));
    }
    static Integer integer(Map<String, String> q, String key) {
        if (!q.containsKey(key)) return null;
        try { return Integer.valueOf(q.get(key)); }
        catch (NumberFormatException ex) { throw BillingRules.invalid(key); }
    }
    static Long id(Map<String, String> q, String key, boolean required) {
        if (!q.containsKey(key)) { if (required) throw BillingRules.invalid(key); return null; }
        try {
            long value = Long.parseLong(q.get(key));
            if (value < 1) throw BillingRules.invalid(key);
            return value;
        } catch (NumberFormatException ex) { throw BillingRules.invalid(key); }
    }
}
