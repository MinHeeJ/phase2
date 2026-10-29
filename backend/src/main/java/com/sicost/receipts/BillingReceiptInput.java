package com.sicost.receipts;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.billing.BillingRules;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import static com.sicost.receipts.BillingReceiptModels.*;

public final class BillingReceiptInput {
    private BillingReceiptInput() {}
    public static ReceiptInput parse(JsonNode node) {
        if (node == null || !node.isObject()) throw BillingRules.invalid(null);
        var allowed = Set.of("rcpt_dt", "rcpt_amt", "rmrk_desc");
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw BillingRules.invalid(key);
        });
        var date = node.path("rcpt_dt");
        if (!date.isTextual() || !date.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw BillingRules.invalid("rcpt_dt");
        }
        LocalDate parsed;
        try { parsed = LocalDate.parse(date.textValue()); }
        catch (DateTimeParseException ex) { throw BillingRules.invalid("rcpt_dt"); }
        var amount = node.path("rcpt_amt");
        if (!amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() < 1) {
            throw BillingRules.invalid("rcpt_amt");
        }
        var remark = node.get("rmrk_desc");
        if (remark != null && !remark.isNull() && !remark.isTextual()) {
            throw BillingRules.invalid("rmrk_desc");
        }
        return new ReceiptInput(parsed, amount.longValue(), BillingRules.text(
            remark == null || remark.isNull() ? null : remark.textValue(), 200, "rmrk_desc"));
    }
    public static long id(long value, String field) {
        if (value < 1) throw BillingRules.invalid(field);
        return value;
    }
}
