package com.sicost.billings;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.validation.BillingValues;
import java.time.LocalDate;
import java.util.*;
import static com.sicost.billings.BillingModels.*;

public final class BillingInput {
    private BillingInput() {}
    public static void id(long id, String field) {
        if (id < 1) throw BillingValues.invalid(field);
    }
    private static void object(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw BillingValues.invalid(null);
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw BillingValues.invalid(field);
        });
    }
    private static Long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw BillingValues.invalid(field);
        return value.longValue();
    }
    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().length() > max) throw BillingValues.invalid(field);
        return value.textValue();
    }
    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return false;
        if (!value.isBoolean()) throw BillingValues.invalid(field);
        return value.booleanValue();
    }
    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field, 10);
        return value == null ? null : BillingValues.date(value, field);
    }
    private static List<Overtime> details(JsonNode node) {
        JsonNode items = node.get("details");
        if (items == null) return List.of();
        if (!items.isArray()) throw BillingValues.invalid("details");
        var result = new ArrayList<Overtime>();
        var ids = new HashSet<Long>();
        for (JsonNode item : items) {
            object(item, Set.of("asgn_id", "ovtm_amt"));
            Long id = number(item, "asgn_id"), amount = number(item, "ovtm_amt");
            if (id == null || id < 1 || !ids.add(id)) throw BillingValues.invalid("asgn_id");
            if (amount == null || amount < 0) throw BillingValues.invalid("ovtm_amt");
            result.add(new Overtime(id, amount));
        }
        return List.copyOf(result);
    }
    public static BillingCreate create(JsonNode node) {
        object(node, Set.of("project_id", "stage_id", "month", "additional", "bill_odr_no",
            "bill_dt", "sply_amt", "vat_amt", "rcpt_plan_dt", "txinv_no", "txinv_dt", "details"));
        Long project = number(node, "project_id"), stage = number(node, "stage_id");
        if (project == null) throw BillingValues.invalid("project_id");
        id(project, "project_id");
        if (stage != null) id(stage, "stage_id");
        Long ordinal = number(node, "bill_odr_no");
        if (ordinal != null && (ordinal < 1 || ordinal > Integer.MAX_VALUE)) {
            throw BillingValues.invalid("bill_odr_no");
        }
        String month = text(node, "month", 7);
        if (month != null) BillingValues.month(month, "month");
        return new BillingCreate(project, stage, month, bool(node, "additional"),
            ordinal == null ? null : ordinal.intValue(), date(node, "bill_dt"), number(node, "sply_amt"),
            number(node, "vat_amt"), date(node, "rcpt_plan_dt"), text(node, "txinv_no", 50),
            date(node, "txinv_dt"), details(node));
    }
    public static BillingUpdate update(JsonNode node) {
        object(node, Set.of("bill_dt", "sply_amt", "vat_amt", "rcpt_plan_dt", "txinv_no",
            "txinv_dt", "rmrk_desc", "recalculate", "details"));
        return new BillingUpdate(date(node, "bill_dt"), number(node, "sply_amt"), number(node, "vat_amt"),
            date(node, "rcpt_plan_dt"), text(node, "txinv_no", 50), date(node, "txinv_dt"),
            text(node, "rmrk_desc", 500), bool(node, "recalculate"), details(node));
    }
}
