package com.sicost.dispatchattendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.validation.BillingRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

public final class DispatchAttendanceInput {
    private DispatchAttendanceInput() {}
    public static Scope scope(String month, Long projectId) {
        var parsed = BillingRules.month(month, "month");
        if (projectId == null || projectId < 1) throw BillingRules.invalid("projectId");
        return new Scope(month, projectId, parsed.atDay(1), parsed.atEndOfMonth());
    }
    public static List<Input> parse(JsonNode body) {
        if (body == null || !body.isObject()) throw BillingRules.invalid("items");
        body.fieldNames().forEachRemaining(k -> {
            if (!k.equals("items")) throw BillingRules.invalid(k);
        });
        var items = body.path("items");
        if (!items.isArray()) throw BillingRules.invalid("items");
        var result = new ArrayList<Input>();
        var developers = new java.util.HashSet<Long>();
        for (int i = 0; i < items.size(); i++) {
            String prefix = "items[" + i + "].";
            var item = items.get(i);
            if (!item.isObject()) throw BillingRules.invalid(prefix);
            var allowed = Set.of("asgn_id", "dev_id", "work_day_qty", "rglr_tm_qty",
                "ovtm_tm_qty", "mth_work_day_qty");
            item.fieldNames().forEachRemaining(k -> {
                if (!allowed.contains(k)) throw BillingRules.invalid(prefix + k);
            });
            long assignment = id(item, "asgn_id", prefix);
            long developer = id(item, "dev_id", prefix);
            if (!developers.add(developer)) throw BillingRules.invalid(prefix + "dev_id");
            result.add(new Input(assignment, developer,
                decimal(item, "work_day_qty", 5, prefix),
                decimal(item, "rglr_tm_qty", 6, prefix),
                decimal(item, "ovtm_tm_qty", 6, prefix),
                decimal(item, "mth_work_day_qty", 5, prefix)));
        }
        return List.copyOf(result);
    }
    private static long id(JsonNode node, String field, String prefix) {
        long id = BillingRules.won(node.get(field), prefix + field);
        if (id < 1) throw BillingRules.invalid(prefix + field);
        return id;
    }
    private static BigDecimal decimal(JsonNode node, String field, int precision, String prefix) {
        var value = node.get(field);
        if (value == null) return null;
        if (!value.isNumber()) throw BillingRules.invalid(prefix + field);
        var number = value.decimalValue();
        try {
            number = number.setScale(1, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) { throw BillingRules.invalid(prefix + field); }
        if (number.signum() < 0 || number.precision() > precision) {
            throw BillingRules.invalid(prefix + field);
        }
        return number;
    }
}
