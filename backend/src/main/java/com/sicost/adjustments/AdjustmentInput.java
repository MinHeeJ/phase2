package com.sicost.adjustments;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.ClosingValues;
import com.sicost.parts.PartSettlementInput;
import java.util.Map;
import java.util.Set;
import static com.sicost.adjustments.AdjustmentModels.*;

public final class AdjustmentInput {
    private AdjustmentInput() {}
    static ApiException invalid(String field) {
        return new ApiException(400, "VALIDATION_ERROR", "입력값을 확인하세요.", field);
    }
    static JsonNode body(ObjectMapper json, String value, Set<String> allowed) {
        JsonNode node = PartSettlementInput.body(json, value);
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid(key); });
        return node;
    }
    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid(field);
        return value.textValue();
    }
    public static String reason(String value) {
        if (value == null || value.strip().isBlank() || value.strip().length() > 500) throw invalid("reason");
        return value.strip();
    }
    static Long optionalId(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw invalid(field);
        return value.longValue();
    }
    public static long id(String value) {
        try { if (value == null || !value.matches("[0-9]{1,19}")) throw invalid("id");
            long id = Long.parseLong(value); if (id <= 0) throw invalid("id"); return id;
        } catch (NumberFormatException ex) { throw invalid("id"); }
    }
    public static Input create(ObjectMapper json, String value) {
        JsonNode n = body(json, value, Set.of("originalMonth", "applyMonth", "type", "amount", "reason",
            "projectId", "developerId", "originalAdjustmentId"));
        String original = ClosingValues.month(text(n,"originalMonth"),"originalMonth").toString();
        String apply = ClosingValues.month(text(n,"applyMonth"),"applyMonth").toString();
        if (original.compareTo(apply) >= 0) throw invalid("originalMonth");
        String type = text(n,"type");
        if (!Set.of("REVENUE","COST","SGA","ETC").contains(type)) throw invalid("type");
        JsonNode amount = n.get("amount");
        if (amount == null || !amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() == 0) throw invalid("amount");
        return new Input(original,apply,type,amount.longValue(),reason(text(n,"reason")),
            optionalId(n,"projectId"),optionalId(n,"developerId"),optionalId(n,"originalAdjustmentId"));
    }
    public static String cancel(ObjectMapper json, String value) {
        return reason(text(body(json,value,Set.of("reason")),"reason"));
    }
    static int integer(String value, String field, int fallback) {
        if (value == null) return fallback;
        try { if (!value.matches("[0-9]+")) throw invalid(field); return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw invalid(field); }
    }
    public static Query query(Map<String,String> values) {
        int year = integer(values.get("year"),"year",0);
        int page = integer(values.get("page"),"page",0), size = integer(values.get("size"),"size",20);
        if (year < 1 || year > 9999) throw invalid("year");
        if (size < 1 || size > 100) throw invalid("size");
        String month = values.get("month");
        if (month != null && ClosingValues.month(month,"month").getYear() != year) throw invalid("month");
        return new Query(year,month,page,size,(long)page*size);
    }
}
