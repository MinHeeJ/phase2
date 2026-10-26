package com.sicost.expenses;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CalendarValues;
import com.sicost.common.validation.ProjectValues;
import java.util.Set;
import static com.sicost.expenses.ProjectExpenseModels.*;

public final class ProjectExpenseInput {
    private ProjectExpenseInput() {}
    public static ExpenseInput parse(JsonNode body) {
        if (body == null || !body.isObject()) throw ApiException.badRequest("입력값을 확인하세요.", null);
        var allowed = Set.of("expns_ym", "expns_item_cd", "expns_amt", "vat_amt", "pay_dt", "rmrk_desc");
        body.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw ApiException.badRequest("변경할 수 없는 항목입니다.", key);
        });
        String month = text(body, "expns_ym", 7, true);
        CalendarValues.month(month, "expns_ym");
        String code = text(body, "expns_item_cd", 30, true);
        long amount = number(body, "expns_amt", 1);
        long vat = body.has("vat_amt") ? number(body, "vat_amt", Long.MIN_VALUE) : ProjectValues.vat(amount);
        String date = text(body, "pay_dt", 10, false);
        return new ExpenseInput(month, code, amount, vat,
            date == null ? null : CalendarValues.date(date, "pay_dt"), text(body, "rmrk_desc", 200, false));
    }
    private static String text(JsonNode body, String field, int limit, boolean required) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            if (required) throw ApiException.badRequest("필수 항목을 입력하세요.", field);
            return null;
        }
        if (!value.isTextual() || value.textValue().length() > limit)
            throw ApiException.badRequest("입력값을 확인하세요.", field);
        String text = value.textValue();
        if (required && text.isBlank()) throw ApiException.badRequest("필수 항목을 입력하세요.", field);
        return text;
    }
    private static long number(JsonNode body, String field, long minimum) {
        JsonNode value = body.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
            throw ApiException.badRequest("원 단위 정수를 입력하세요.", field);
        return ProjectValues.money(value.decimalValue(), minimum, field);
    }
    public static long id(long id, String field) {
        if (id < 1) throw ApiException.badRequest("식별자를 확인하세요.", field);
        return id;
    }
}
