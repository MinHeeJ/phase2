package com.sicost.parts;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.costallocation.CostAnalysisInput;
import java.util.Set;
import static com.sicost.parts.PartSettlementModels.*;

public final class PartSettlementInput {
    private PartSettlementInput() {}
    public static Key key(String id, String month) {
        return new Key(CostAnalysisInput.id(id, "partId"), CostAnalysisInput.month(month));
    }
    public static String view(String value) {
        if (value == null) return "MONTH";
        if (!Set.of("MONTH", "TREND").contains(value)) throw CostAnalysisInput.invalid("view");
        return value;
    }
    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw CostAnalysisInput.invalid(null);
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw CostAnalysisInput.invalid(name);
        });
    }
    public static Card card(JsonNode node) {
        fields(node, Set.of("corp_card_amt", "note"));
        var value = node.get("corp_card_amt");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw CostAnalysisInput.invalid("corp_card_amt");
        String note = null;
        if (node.has("note")) {
            if (!node.get("note").isTextual() || node.get("note").textValue().length() > 500)
                throw CostAnalysisInput.invalid("note");
            note = node.get("note").textValue().trim();
        }
        return new Card(value.longValue(), note, node.has("note"));
    }
    public static String reason(JsonNode node) {
        fields(node, Set.of("reason"));
        var reason = node.get("reason");
        if (reason == null || !reason.isTextual() || reason.textValue().isBlank()
            || reason.textValue().length() > 500) throw CostAnalysisInput.invalid("reason");
        return reason.textValue().trim();
    }
    public static void confirmation(JsonNode node) {
        fields(node, Set.of());
    }
}
