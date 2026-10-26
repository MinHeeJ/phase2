package com.sicost.allocations;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CalendarValues;
import com.sicost.common.validation.ProjectValues;
import java.util.*;
import static com.sicost.allocations.ProjectAllocationModels.*;

public final class ProjectAllocationInput {
    private ProjectAllocationInput() {}

    public static String stage(String stage, boolean required) {
        if (stage == null && !required) return null;
        if (stage == null || !Set.of("EST", "FINAL", "ACTUAL").contains(stage)) {
            throw ApiException.badRequest("배분 단계를 골라 주세요.", "stage");
        }
        return stage;
    }
    public static List<AllocationLine> parse(JsonNode body) {
        object(body, Set.of("items"), "items");
        JsonNode items = body.get("items");
        if (items == null || !items.isArray()) throw invalid("items");
        List<AllocationLine> lines = new ArrayList<>();
        for (JsonNode n : items) {
            object(n, Set.of("line_ty_cd", "dev_id", "item_nm", "job_cd", "asgn_bgn_dt",
                    "asgn_se_cd", "rmrk_desc", "sort_seq", "months"), "items");
            String type = text(n, "line_ty_cd", 10, true);
            if (!Set.of("PERSON", "ITEM").contains(type)) throw invalid("line_ty_cd");
            boolean person = type.equals("PERSON");
            Long dev = number(n, "dev_id");
            if (person && (dev == null || dev < 1)) throw invalid("dev_id");
            String job = text(n, "job_cd", 30, person);
            String date = text(n, "asgn_bgn_dt", 10, person);
            String item = text(n, "item_nm", 100, !person);
            String kind = text(n, "asgn_se_cd", 10, false);
            String remark = text(n, "rmrk_desc", 200, false);
            int sort = lines.size();
            if (n.has("sort_seq")) {
                if (!n.get("sort_seq").isIntegralNumber() || !n.get("sort_seq").canConvertToInt()) throw invalid("sort_seq");
                sort = n.get("sort_seq").intValue();
            }
            JsonNode months = n.get("months");
            if (months == null || !months.isArray()) throw invalid("months");
            List<AllocationMonth> cells = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode m : months) {
                object(m, Set.of("aloc_ym", "aloc_amt", "mm_qty"), "months");
                String month = text(m, "aloc_ym", 7, true);
                CalendarValues.month(month, "aloc_ym");
                if (!seen.add(month)) throw invalid("aloc_ym");
                Long amount = number(m, "aloc_amt");
                java.math.BigDecimal mm = null;
                if (m.hasNonNull("mm_qty")) {
                    if (!m.get("mm_qty").isNumber()) throw invalid("mm_qty");
                    mm = ProjectValues.decimal(m.get("mm_qty").decimalValue(), 5, "mm_qty");
                }
                if (amount != null || mm != null) cells.add(new AllocationMonth(month, amount, mm));
            }
            lines.add(new AllocationLine(type, person ? dev : null, person ? null : item,
                    person ? job : null, date == null ? null : CalendarValues.date(date, "asgn_bgn_dt"),
                    kind, remark, sort, List.copyOf(cells)));
        }
        return List.copyOf(lines);
    }
    private static void object(JsonNode n, Set<String> keys, String field) {
        if (n == null || !n.isObject()) throw invalid(field);
        n.fieldNames().forEachRemaining(k -> { if (!keys.contains(k)) throw invalid(k); });
    }
    private static String text(JsonNode n, String key, int max, boolean required) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) { if (required) throw invalid(key); return null; }
        if (!v.isTextual()) throw invalid(key);
        String value = v.textValue().trim();
        if (value.length() > max || (required && value.isEmpty())) throw invalid(key);
        return value.isEmpty() ? null : value;
    }
    private static Long number(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return null;
        if (!v.isIntegralNumber() || !v.canConvertToLong()) throw invalid(key);
        return v.longValue();
    }
    private static ApiException invalid(String field) {
        return ApiException.badRequest("입력값을 확인하세요.", field);
    }
}
