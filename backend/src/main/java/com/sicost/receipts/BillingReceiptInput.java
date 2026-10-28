package com.sicost.receipts;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.validation.BillingRules;
import java.time.LocalDate;
import java.util.Set;
import static com.sicost.receipts.BillingReceiptModels.*;

public final class BillingReceiptInput {
    private BillingReceiptInput() {}
    public static long id(long value, String field) {
        if (value < 1) throw BillingRules.invalid(field);
        return value;
    }
    public static ReceiptInput parse(JsonNode node, boolean update) {
        if (node == null || !node.isObject()) throw BillingRules.invalid(null);
        var allowed = Set.of("receipt_id", "rcpt_dt", "rcpt_amt", "rmrk_desc");
        node.fieldNames().forEachRemaining(k -> {
            if (!allowed.contains(k)) throw BillingRules.invalid(k);
        });
        if (node.has("receipt_id")) {
            id(BillingRules.won(node.get("receipt_id"), "receipt_id"), "receipt_id");
        }
        LocalDate date = null;
        if (node.has("rcpt_dt") || !update) {
            var value = node.path("rcpt_dt");
            if (!value.isTextual()) throw BillingRules.invalid("rcpt_dt");
            date = BillingRules.date(value.textValue(), "rcpt_dt");
        }
        Long amount = null;
        if (node.has("rcpt_amt") || !update) {
            amount = BillingRules.won(node.get("rcpt_amt"), "rcpt_amt");
            if (amount <= 0) throw BillingRules.invalid("rcpt_amt");
        }
        String note = null;
        if (node.hasNonNull("rmrk_desc")) {
            var value = node.get("rmrk_desc");
            if (!value.isTextual() || value.textValue().length() > 200) {
                throw BillingRules.invalid("rmrk_desc");
            }
            note = value.textValue();
        }
        return new ReceiptInput(date, amount, note, node.has("rmrk_desc"));
    }
}
