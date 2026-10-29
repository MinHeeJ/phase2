package com.sicost.billings;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.validation.BillingValues;
import java.time.LocalDate;
import java.util.*;
import static com.sicost.billings.BillingModels.*;

public final class BillingInput {
    private BillingInput() {}
    public static Input parse(JsonNode body, boolean create) {
        if (body == null || !body.isObject()) throw BillingValues.invalid(null);
        Set<String> allowed = new HashSet<>(Set.of("bill_dt", "sply_amt", "vat_amt", "rcpt_plan_dt",
            "txinv_no", "txinv_dt", "details"));
        if (create) allowed.addAll(Set.of("project_id", "stage_id", "month", "additional", "bill_odr_no"));
        else allowed.addAll(Set.of("rmrk_desc", "recalculate"));
        Set<String> present = new HashSet<>();
        body.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw BillingValues.invalid(key);
            present.add(key);
        });
        Long project = number(body, "project_id", 1);
        if (create && project == null) throw BillingValues.invalid("project_id");
        String month = text(body, "month", 7);
        if (month != null) BillingValues.month(month, "month");
        Long ordinal = number(body, "bill_odr_no", 1);
        if (ordinal != null && ordinal > Integer.MAX_VALUE) throw BillingValues.invalid("bill_odr_no");
        List<Overtime> details = new ArrayList<>();
        if (body.has("details")) {
            if (!body.get("details").isArray()) throw BillingValues.invalid("details");
            Set<Long> ids = new HashSet<>();
            for (JsonNode item : body.get("details")) {
                if (!item.isObject()) throw BillingValues.invalid("details");
                item.fieldNames().forEachRemaining(k -> {
                    if (!Set.of("asgn_id", "ovtm_amt").contains(k)) throw BillingValues.invalid("details." + k);
                });
                Long id = number(item, "asgn_id", 1), overtime = number(item, "ovtm_amt", 0);
                if (id == null || overtime == null || !ids.add(id)) throw BillingValues.invalid("details");
                details.add(new Overtime(id, overtime));
            }
        }
        return new Input(project, number(body, "stage_id", 1), month, bool(body, "additional"),
            ordinal == null ? null : ordinal.intValue(), date(body, "bill_dt"),
            number(body, "sply_amt", Long.MIN_VALUE), number(body, "vat_amt", Long.MIN_VALUE),
            date(body, "rcpt_plan_dt"), text(body, "txinv_no", 50), date(body, "txinv_dt"),
            text(body, "rmrk_desc", 500), bool(body, "recalculate"), List.copyOf(details), Set.copyOf(present));
    }
    private static Long number(JsonNode body, String key, long minimum) {
        if (!body.has(key)) return null;
        return BillingValues.money(body.get(key), minimum, key);
    }
    private static String text(JsonNode body, String key, int limit) {
        JsonNode value = body.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().length() > limit) throw BillingValues.invalid(key);
        return value.textValue();
    }
    private static LocalDate date(JsonNode body, String key) {
        String value = text(body, key, 10);
        return value == null ? null : BillingValues.date(value, key);
    }
    private static boolean bool(JsonNode body, String key) {
        if (!body.has(key)) return false;
        if (!body.get(key).isBoolean()) throw BillingValues.invalid(key);
        return body.get(key).booleanValue();
    }
    public static long id(long id) {
        if (id < 1) throw BillingValues.invalid("id");
        return id;
    }
}
