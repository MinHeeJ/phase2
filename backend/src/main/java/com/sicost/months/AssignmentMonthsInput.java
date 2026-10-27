package com.sicost.months;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.AssignmentRules;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.sicost.months.AssignmentMonthsModels.*;

public final class AssignmentMonthsInput {
    private AssignmentMonthsInput() {}

    static ApiException invalid(String field) {
        return ApiException.badRequest("입력값을 확인하세요.", field);
    }
    static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.badRequest("MM을 0~1.00 사이 숫자로 입력하세요.", "mm_qty");
        }
        return AssignmentRules.manualMm(value);
    }
    public static MonthsInput parse(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid(null);
        keys(node, Set.of("updated_at", "months"));
        LocalDateTime version;
        var stamp = node.path("updated_at");
        if (!stamp.isTextual()) throw invalid("updated_at");
        try { version = LocalDateTime.parse(stamp.textValue()); }
        catch (DateTimeParseException ex) { throw invalid("updated_at"); }
        var months = node.path("months");
        if (!months.isArray()) throw invalid("months");
        var result = new ArrayList<MonthInput>();
        var seen = new HashSet<String>();
        for (var item : months) {
            if (!item.isObject()) throw invalid("months");
            keys(item, Set.of("work_ym", "mm_qty"));
            var month = item.path("work_ym");
            if (!month.isTextual() || !month.textValue().matches("[0-9]{4}-(0[1-9]|1[0-2])")
                    || !seen.add(month.textValue())) throw invalid("work_ym");
            var value = item.path("mm_qty");
            if (!value.isNumber()) quantity(null);
            result.add(new MonthInput(month.textValue(), quantity(value.decimalValue())));
        }
        return new MonthsInput(version, List.copyOf(result));
    }
    private static void keys(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw invalid(key);
        });
    }
}
