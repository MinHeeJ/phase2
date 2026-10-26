package com.sicost.staffplans;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CalendarValues;
import com.sicost.common.validation.ProjectValues;
import java.util.*;
import static com.sicost.staffplans.ProjectStaffPlanModels.*;

public final class ProjectStaffPlanInput {
    private ProjectStaffPlanInput() {}
    public static List<StaffPlanInput> parse(JsonNode root) {
        if (root == null || !root.isObject()) throw invalid("items");
        root.fieldNames().forEachRemaining(key -> { if (!key.equals("items")) throw invalid(key); });
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) throw invalid("items");
        List<StaffPlanInput> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode row = items.get(i);
            String prefix = "items[" + i + "].";
            if (!row.isObject()) throw invalid("items");
            var allowed = Set.of("plan_ym", "job_cd", "grd_cd", "need_mm_qty", "expc_mth_cst_amt");
            row.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid(prefix + key); });
            String month = text(row, "plan_ym", prefix, true);
            CalendarValues.month(month, prefix + "plan_ym");
            String job = text(row, "job_cd", prefix, true);
            String grade = text(row, "grd_cd", prefix, false);
            JsonNode mm = row.get("need_mm_qty");
            if (mm == null || !mm.isNumber()) throw invalid(prefix + "need_mm_qty");
            var quantity = ProjectValues.decimal(mm.decimalValue(), 4, prefix + "need_mm_qty");
            if (quantity.signum() < 0) throw invalid(prefix + "need_mm_qty");
            JsonNode cost = row.get("expc_mth_cst_amt");
            Long amount = null;
            if (cost != null && !cost.isNull()) {
                if (!cost.isIntegralNumber() || !cost.canConvertToLong()) throw invalid(prefix + "expc_mth_cst_amt");
                amount = cost.longValue();
            }
            result.add(new StaffPlanInput(month, job, grade == null ? "" : grade, quantity, amount));
        }
        return List.copyOf(result);
    }
    private static String text(JsonNode row, String key, String prefix, boolean required) {
        JsonNode node = row.get(key);
        if (node == null) { if (required) throw invalid(prefix + key); return null; }
        if (!node.isTextual()) throw invalid(prefix + key);
        String value = node.textValue().trim();
        if (value.length() > 30 || (required && value.isEmpty())) throw invalid(prefix + key);
        return value;
    }
    private static ApiException invalid(String field) {
        return ApiException.badRequest("입력값을 확인하세요.", field);
    }
}
