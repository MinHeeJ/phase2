package com.sicost.parts;

import com.fasterxml.jackson.databind.*;
import com.sicost.billingsops.BillingInput;
import com.sicost.common.validation.*;
import java.util.Set;
import static com.sicost.parts.PartSettlementModels.*;

public final class PartSettlementInput {
    private PartSettlementInput() {}
    public static long id(String value) {
        Long id = CostValues.optionalId(value, "partId");
        if (id == null) throw BillingValues.invalid("partId");
        return id;
    }
    public static JsonNode body(ObjectMapper json, String body) {
        if (body == null || body.isBlank()) throw BillingValues.invalid(null);
        try {
            JsonNode node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (node == null || !node.isObject()) throw BillingValues.invalid(null);
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw BillingValues.invalid(null);
        }
    }
    public static Card card(JsonNode node) {
        node.fieldNames().forEachRemaining(key -> {
            if (!Set.of("corp_card_amt", "rmrk_desc").contains(key)) throw BillingValues.invalid(key);
        });
        JsonNode amount = node.get("corp_card_amt");
        if (amount == null || !amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() < 0) {
            throw BillingValues.invalid("corp_card_amt");
        }
        JsonNode remark = node.get("rmrk_desc");
        if (remark != null && !remark.isNull() && (!remark.isTextual() || remark.textValue().length() > 500)) {
            throw BillingValues.invalid("rmrk_desc");
        }
        return new Card(amount.longValue(), remark == null || remark.isNull() ? null : remark.textValue());
    }
    public static String cancel(JsonNode node) {
        node.fieldNames().forEachRemaining(key -> {
            if (!"reason".equals(key)) throw BillingValues.invalid(key);
        });
        return BillingInput.cancel(node);
    }
}
