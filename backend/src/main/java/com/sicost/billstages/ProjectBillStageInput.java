package com.sicost.billstages;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.ProjectValues;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import static com.sicost.billstages.ProjectBillStageModels.*;

public final class ProjectBillStageInput {
    private ProjectBillStageInput() {}

    public static List<StageInput> parse(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("items").isArray()) {
            throw ApiException.badRequest("청구 단계 목록을 확인하세요.", "items");
        }
        if (body.get("items").size() > 20) {
            throw ApiException.badRequest("청구 단계는 최대 20건입니다.", "items");
        }
        var result = new ArrayList<StageInput>();
        for (JsonNode row : body.get("items")) {
            int index = result.size();
            String prefix = "items[" + index + "].";
            if (!row.isObject()) throw ApiException.badRequest("청구 단계를 확인하세요.", "items");
            Long id = null;
            if (row.has("bill_stg_id")) {
                var value = row.get("bill_stg_id");
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
                    throw ApiException.badRequest("단계 식별자를 확인하세요.", prefix + "bill_stg_id");
                }
                id = value.longValue();
            }
            var name = row.path("bill_stg_nm");
            if (!name.isTextual() || name.textValue().isBlank() || name.textValue().length() > 100) {
                throw ApiException.badRequest("단계명은 필수이며 100자 이하로 입력하세요.", prefix + "bill_stg_nm");
            }
            var amount = row.path("sply_amt");
            if (!amount.isIntegralNumber()) {
                throw ApiException.badRequest("공급가액은 1원 이상의 정수로 입력하세요.", prefix + "sply_amt");
            }
            long money = ProjectValues.money(amount.decimalValue(), 1, prefix + "sply_amt");
            var date = row.path("bill_plan_dt");
            LocalDate planned;
            try {
                if (!date.isTextual() || !date.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    throw new DateTimeParseException("date", "", 0);
                }
                planned = LocalDate.parse(date.textValue());
                if (planned.getYear() < 1) throw new DateTimeParseException("date", "", 0);
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest("청구 예정일을 입력하세요.", prefix + "bill_plan_dt");
            }
            result.add(new StageInput(id, name.textValue(), money, planned));
        }
        return List.copyOf(result);
    }
}
