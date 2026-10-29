package com.sicost.billings;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.billing.BillingRules;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.sicost.billings.BillingModels.*;

public final class BillingInput {
    private BillingInput() {}
    private static final Set<String> COMMON = Set.of("bill_dt", "sply_amt", "vat_amt", "rcpt_plan_dt",
        "txinv_no", "txinv_dt", "details");
    public static Input parse(JsonNode node, boolean create) {
        if (node == null || !node.isObject()) throw BillingRules.invalid(null);
        Set<String> allowed = new HashSet<>(COMMON);
        allowed.addAll(create ? Set.of("project_id", "stage_id", "month", "additional", "bill_odr_no")
            : Set.of("rmrk_desc", "recalculate", "project_id", "stage_id", "month"));
        Map<String, Object> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            String key = e.getKey(); JsonNode value = e.getValue();
            if (!allowed.contains(key)) throw BillingRules.invalid(key);
            if (key.equals("details")) return;
            Object parsed;
            if (Set.of("project_id", "stage_id", "bill_odr_no", "sply_amt", "vat_amt").contains(key)) {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) throw BillingRules.invalid(key);
                long n = value.longValue();
                if (Set.of("project_id", "stage_id", "bill_odr_no").contains(key) && n < 1) throw BillingRules.invalid(key);
                if (key.equals("bill_odr_no") && n > Integer.MAX_VALUE) throw BillingRules.invalid(key);
                parsed = n;
            } else if (key.equals("additional") || key.equals("recalculate")) {
                if (!value.isBoolean()) throw BillingRules.invalid(key);
                parsed = value.booleanValue();
            } else if (key.endsWith("_dt")) {
                if (value.isNull()) parsed = null;
                else {
                    if (!value.isTextual() || !value.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw BillingRules.invalid(key);
                    try {
                        LocalDate date = LocalDate.parse(value.textValue());
                        if (date.getYear() < 1) throw BillingRules.invalid(key);
                        parsed = date;
                    }
                    catch (DateTimeParseException ex) { throw BillingRules.invalid(key); }
                }
            } else {
                if (!value.isNull() && !value.isTextual()) throw BillingRules.invalid(key);
                parsed = value.isNull() ? null : value.textValue();
                if (key.equals("month") && parsed != null) BillingRules.month((String) parsed, key);
                BillingRules.text((String) parsed, key.equals("txinv_no") ? 50 : key.equals("month") ? 7 : 500, key);
            }
            values.put(key, parsed);
        });
        if (create && !values.containsKey("project_id")) throw BillingRules.invalid("project_id");
        List<Overtime> details = new ArrayList<>();
        JsonNode lines = node.get("details");
        if (lines != null) {
            if (!lines.isArray()) throw BillingRules.invalid("details");
            Set<Long> used = new HashSet<>();
            for (JsonNode line : lines) {
                if (!line.isObject()) throw BillingRules.invalid("details");
                line.fieldNames().forEachRemaining(k -> { if (!Set.of("asgn_id", "ovtm_amt").contains(k)) throw BillingRules.invalid(k); });
                JsonNode id = line.path("asgn_id"), amount = line.path("ovtm_amt");
                if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 1 || !used.add(id.longValue())) throw BillingRules.invalid("asgn_id");
                if (!amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() < 0) throw BillingRules.invalid("ovtm_amt");
                details.add(new Overtime(id.longValue(), amount.longValue()));
            }
        }
        boolean recalculate = Boolean.TRUE.equals(values.get("recalculate"));
        if (!create && lines != null && !recalculate) throw BillingRules.invalid("recalculate");
        return new Input(Collections.unmodifiableMap(values), List.copyOf(details), recalculate);
    }
    public static Query query(Map<String, String> input, LocalDate today) {
        int year = integer(input.get("year"), today.getYear(), "year");
        int page = integer(input.get("page"), 0, "page");
        int size = integer(input.get("size"), 50, "size");
        if (year < 1 || year > 9998) throw BillingRules.invalid("year");
        if (page < 0) throw BillingRules.invalid("page");
        if (!Set.of(20, 50, 100).contains(size)) throw BillingRules.invalid("size");
        String status = clean(input.get("status")), type = clean(input.get("type"));
        if (status != null && !Set.of("PLAN", "DRAFT", "CONFIRM", "CANCEL", "PAID", "UNPAID", "OVERDUE").contains(status)) throw BillingRules.invalid("status");
        if (type != null) BillingRules.billable(type);
        Long customer = null;
        if (clean(input.get("customerId")) != null) {
            try { customer = Long.parseLong(input.get("customerId")); }
            catch (NumberFormatException ex) { throw BillingRules.invalid("customerId"); }
            if (customer < 1) throw BillingRules.invalid("customerId");
        }
        String[] sort = input.getOrDefault("sort", "bill_dt,desc").split(",", -1);
        if (sort.length != 2 || !Set.of("bill_no", "project_name", "bill_dt", "sply_amt", "vat_amt", "tot_amt",
            "rcpt_plan_dt", "received_amt", "receivable_amt", "bill_stts_cd", "atrb_ym").contains(sort[0])
            || !Set.of("asc", "desc").contains(sort[1])) throw BillingRules.invalid("sort");
        String q = BillingRules.text(clean(input.get("q")), 200, "q");
        LocalDate start = LocalDate.of(year, 1, 1);
        return new Query(year, start, start.plusYears(1), status, type, customer, q,
            page, size, (long) page * size, sort[0], sort[1], today);
    }
    private static int integer(String value, int fallback, String field) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw BillingRules.invalid(field); }
    }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
