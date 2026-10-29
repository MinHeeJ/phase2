package com.sicost.dispatchattendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.billing.BillingRules;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

public final class DispatchAttendanceInput {
    private DispatchAttendanceInput() {}
    private static void fields(JsonNode node, Set<String> allowed, String prefix) {
        if (node == null || !node.isObject()) throw BillingRules.invalid(prefix);
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw BillingRules.invalid(prefix + name);
        });
    }
    private static long id(JsonNode node, String field) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()
                || node.longValue() < 1) throw BillingRules.invalid(field);
        return node.longValue();
    }
    private static BigDecimal quantity(JsonNode node, String field) {
        if (node == null) return null;
        if (!node.isNumber()) throw BillingRules.invalid(field);
        return node.decimalValue();
    }
    public static List<Input> parse(JsonNode body) {
        fields(body, Set.of("items"), "");
        JsonNode items = body.get("items");
        if (items == null || !items.isArray()) throw BillingRules.invalid("items");
        var result = new ArrayList<Input>();
        for (int i = 0; i < items.size(); i++) {
            var row = items.get(i);
            String prefix = "items[" + i + "].";
            fields(row, Set.of("asgn_id", "dev_id", "work_day_qty", "rglr_tm_qty",
                "ovtm_tm_qty", "mth_work_day_qty"), prefix);
            result.add(new Input(id(row.get("asgn_id"), prefix + "asgn_id"),
                id(row.get("dev_id"), prefix + "dev_id"),
                quantity(row.get("work_day_qty"), prefix + "work_day_qty"),
                quantity(row.get("rglr_tm_qty"), prefix + "rglr_tm_qty"),
                quantity(row.get("ovtm_tm_qty"), prefix + "ovtm_tm_qty"),
                quantity(row.get("mth_work_day_qty"), prefix + "mth_work_day_qty")));
        }
        return List.copyOf(result);
    }
}
