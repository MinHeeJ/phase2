package com.sicost.contractchanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.ProjectValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import static com.sicost.contractchanges.ProjectContractChangeModels.*;

public final class ProjectContractChangeInput {
    private ProjectContractChangeInput() {}
    public static long id(long value, String field) {
        if (value < 1) throw error(field);
        return value;
    }
    static ApiException error(String field) {
        return ApiException.badRequest("입력값을 확인하세요.", field);
    }
    public static ChangeInput parse(JsonNode node) {
        if (node == null || !node.isObject()) throw error(null);
        var allowed = Set.of("cntrct_chg_ty_cd", "cntrct_chg_stts_cd", "chg_dt", "amount",
            "incdec_mm_qty", "aft_end_dt", "free_mm_qty", "rsn_desc");
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw error(key); });
        String type = text(node, "cntrct_chg_ty_cd", 10, true);
        String status = text(node, "cntrct_chg_stts_cd", 10, true);
        if (!Set.of("ADD", "CUT", "TERM", "FREE").contains(type)) throw error("cntrct_chg_ty_cd");
        if (!Set.of("PLAN", "CONFIRM").contains(status)) throw error("cntrct_chg_stts_cd");
        Long amount = null;
        if (node.has("amount")) {
            var number = node.get("amount");
            if (!number.isIntegralNumber() || !number.canConvertToLong()) throw error("amount");
            amount = ProjectValues.money(number.decimalValue(), 1, "amount");
        }
        if ((type.equals("ADD") || type.equals("CUT")) && amount == null) throw error("amount");
        LocalDate end = date(node, "aft_end_dt", type.equals("TERM"));
        BigDecimal mm = decimal(node, "incdec_mm_qty", false);
        BigDecimal free = decimal(node, "free_mm_qty", type.equals("FREE"));
        return new ChangeInput(type, status, date(node, "chg_dt", true), amount, mm, end, free,
            text(node, "rsn_desc", 500, true));
    }
    private static String text(JsonNode node, String key, int max, boolean required) {
        var value = node.get(key);
        if (value == null || value.isNull()) { if (required) throw error(key); return null; }
        if (!value.isTextual()) throw error(key);
        String result = value.textValue().trim();
        if (result.length() > max || (required && result.isEmpty())) throw error(key);
        return result.isEmpty() ? null : result;
    }
    private static LocalDate date(JsonNode node, String key, boolean required) {
        String value = text(node, key, 10, required);
        if (value == null) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw error(key); }
    }
    private static BigDecimal decimal(JsonNode node, String key, boolean required) {
        var value = node.get(key);
        if (value == null || value.isNull()) { if (required) throw error(key); return null; }
        if (!value.isNumber()) throw error(key);
        return ProjectValues.decimal(value.decimalValue(), 8, key);
    }
}
