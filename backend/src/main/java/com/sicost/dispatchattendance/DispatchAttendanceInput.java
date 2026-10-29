package com.sicost.dispatchattendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.validation.BillingValues;
import java.math.BigDecimal;
import java.util.*;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

public final class DispatchAttendanceInput {
    private DispatchAttendanceInput() {}
    public static Scope scope(String month, String projectId) {
        var ym = BillingValues.month(month, "month");
        try {
            if (projectId == null || !projectId.matches("[0-9]{1,19}")) throw BillingValues.invalid("projectId");
            long id = Long.parseLong(projectId);
            if (id < 1) throw BillingValues.invalid("projectId");
            return new Scope(month, id, ym.atDay(1), ym.atEndOfMonth());
        } catch (NumberFormatException ex) { throw BillingValues.invalid("projectId"); }
    }
    public static List<AttendanceInput> parse(JsonNode body) {
        fields(body, Set.of("items"), "items");
        var rows = body.get("items");
        if (rows == null || !rows.isArray()) throw BillingValues.invalid("items");
        List<AttendanceInput> inputs = new ArrayList<>();
        Set<Long> people = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            String prefix = "items[" + i + "].";
            fields(row, Set.of("asgn_id", "dev_id", "work_day_qty", "rglr_tm_qty", "ovtm_tm_qty", "mth_work_day_qty"), prefix);
            long assignment = id(row.get("asgn_id"), prefix + "asgn_id");
            long person = id(row.get("dev_id"), prefix + "dev_id");
            if (!people.add(person)) throw BillingValues.invalid(prefix + "dev_id");
            inputs.add(new AttendanceInput(assignment, person,
                quantity(row, "work_day_qty", prefix, false, "9999.9"),
                quantity(row, "rglr_tm_qty", prefix, false, "99999.9"),
                row.hasNonNull("ovtm_tm_qty") ? quantity(row, "ovtm_tm_qty", prefix, false, "99999.9") : BigDecimal.ZERO,
                quantity(row, "mth_work_day_qty", prefix, true, "31")));
        }
        return List.copyOf(inputs);
    }
    private static long id(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1)
            throw BillingValues.invalid(field);
        return value.longValue();
    }
    private static void fields(JsonNode body, Set<String> allowed, String prefix) {
        if (body == null || !body.isObject()) throw BillingValues.invalid(prefix);
        body.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw BillingValues.invalid(prefix + key);
        });
    }
    private static BigDecimal quantity(JsonNode row, String key, String prefix, boolean required, String maximum) {
        var value = row.get(key);
        if (value == null || value.isNull()) {
            if (required) throw BillingValues.invalid(prefix + key);
            return null;
        }
        if (!value.isNumber()) throw BillingValues.invalid(prefix + key);
        var number = value.decimalValue();
        if (number.signum() < 0 || (required && number.signum() == 0)
                || number.stripTrailingZeros().scale() > 1 || number.compareTo(new BigDecimal(maximum)) > 0)
            throw BillingValues.invalid(prefix + key);
        return number;
    }
}
