package com.sicost.receipts;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.validation.BillingValues;
import java.util.Set;
import static com.sicost.receipts.BillingReceiptModels.*;

public final class BillingReceiptInput {
    private BillingReceiptInput() {}
    public static long id(String value, String field) {
        try { return id(Long.parseLong(value), field); }
        catch (NumberFormatException ex) { throw BillingValues.invalid(field); }
    }
    public static long id(long value, String field) {
        if (value < 1) throw BillingValues.invalid(field);
        return value;
    }
    public static ReceiptInput parse(JsonNode body) {
        if (body == null || !body.isObject()) throw BillingValues.invalid(null);
        var allowed = Set.of("receipt_id", "rcpt_dt", "rcpt_amt", "rmrk_desc");
        body.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw BillingValues.invalid(key);
        });
        Long receiptId = body.has("receipt_id")
            ? BillingValues.money(body.get("receipt_id"), 1, "receipt_id") : null;
        String date = text(body, "rcpt_dt", 10, true);
        return new ReceiptInput(receiptId, BillingValues.date(date, "rcpt_dt"),
            BillingValues.money(body.get("rcpt_amt"), 1, "rcpt_amt"),
            text(body, "rmrk_desc", 200, false));
    }
    private static String text(JsonNode body, String field, int limit, boolean required) {
        var value = body.get(field);
        if (value == null || value.isNull()) {
            if (required) throw BillingValues.invalid(field);
            return null;
        }
        if (!value.isTextual() || value.textValue().length() > limit
                || (required && value.textValue().isBlank())) throw BillingValues.invalid(field);
        return value.textValue();
    }
    public static void validate(ReceiptInput input, Long pathId) {
        if (input == null || input.rcpt_dt() == null) throw BillingValues.invalid("rcpt_dt");
        BillingValues.date(input.rcpt_dt().toString(), "rcpt_dt");
        if (input.rcpt_amt() < 1) throw BillingValues.invalid("rcpt_amt");
        if (input.rmrk_desc() != null && input.rmrk_desc().length() > 200)
            throw BillingValues.invalid("rmrk_desc");
        if (input.receipt_id() != null && !input.receipt_id().equals(pathId))
            throw BillingValues.invalid("receipt_id");
    }
}
