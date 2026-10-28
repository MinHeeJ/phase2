package com.sicost.receipts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.time.*;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.receipts.BillingReceiptModels.*;

@Service
@Transactional(readOnly = true)
public class BillingReceiptService {
    private final BillingReceiptMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ObjectMapper json;

    public BillingReceiptService(BillingReceiptMapper mapper, CurrentUser user,
            PermissionService permissions, Clock clock, ObjectMapper json) {
        this.mapper = mapper;
        this.user = user;
        this.permissions = permissions;
        this.clock = clock;
        this.json = json;
    }
    private void action(String operation) {
        if (!BillingActions.can(permissions, operation)) {
            throw new ApiException(403, "FORBIDDEN", "입금 권한이 없습니다.", null);
        }
    }
    private BillState require(long id, boolean lock) {
        if (id < 1) throw BillingValues.invalid("id");
        var bill = lock ? mapper.lockBill(id) : mapper.bill(id);
        if (bill == null) throw BillingValues.invalid("id");
        return bill;
    }
    private BillState writable(long id) {
        var bill = require(id, true);
        BillingRules.requireOriginalConfirmed(bill.bill_stts_cd(),
            "Y".equals(bill.amnd_yn()) || bill.orgnl_bill_id() != null);
        return bill;
    }
    private ReceiptRow require(ReceiptKey key) {
        if (key.receiptId() < 1) throw BillingValues.invalid("receiptId");
        var row = mapper.receipt(key);
        if (row == null) throw BillingValues.invalid("receiptId");
        return row;
    }
    private void open(LocalDate date) {
        if (date == null) throw BillingValues.invalid("rcpt_dt");
        var month = YearMonth.from(date);
        BillingRules.requireOpen(month, "CLOSED".equals(mapper.monthStatus(month.toString()))
            ? Set.of(month) : Set.of(), "rcpt_dt");
        if (BillingRules.pastYear(date.getYear(), clock)) throw BillingValues.invalid("rcpt_dt");
    }
    private void validate(ReceiptInput input) {
        if (input == null || input.rcpt_dt() == null) throw BillingValues.invalid("rcpt_dt");
        if (input.rcpt_amt() < 1) throw BillingValues.invalid("rcpt_amt");
        if (input.rmrk_desc() != null && input.rmrk_desc().length() > 200) throw BillingValues.invalid("rmrk_desc");
    }
    private void balance(BillState bill, long excluded, long requested) {
        long adjusted = BillingAmounts.adjustedTotal(bill.tot_amt(), mapper.amendmentTotal(bill.bill_id()));
        long received = mapper.receivedExcluding(new ReceiptKey(bill.bill_id(), excluded));
        if (requested > 0) BillingAmounts.requireReceipt(adjusted, received, requested);
        else BillingAmounts.requireRefundSafe(adjusted, received);
    }
    public ReceiptDetail list(long id) {
        action("listBillingReceipts");
        return detail(require(id, false));
    }
    @Transactional
    public ReceiptDetail create(long id, ReceiptInput input) {
        action("createBillingReceipt");
        validate(input);
        var bill = writable(id);
        open(input.rcpt_dt());
        balance(bill, 0, input.rcpt_amt());
        long receiptId = mapper.insert(new ReceiptWrite(id, null, input, user.userId()));
        var saved = require(new ReceiptKey(id, receiptId));
        audit(id, "INSERT", null, saved);
        return detail(bill);
    }
    @Transactional
    public ReceiptDetail update(long id, long receiptId, ReceiptInput input) {
        action("updateBillingReceipt");
        validate(input);
        var bill = writable(id);
        var key = new ReceiptKey(id, receiptId);
        var before = require(key);
        open(before.rcpt_dt());
        open(input.rcpt_dt());
        balance(bill, receiptId, input.rcpt_amt());
        BillingRules.requireChanged(mapper.update(new ReceiptWrite(id, receiptId, input, user.userId())));
        audit(id, "UPDATE", before, require(key));
        return detail(bill);
    }
    @Transactional
    public ReceiptDetail delete(long id, long receiptId) {
        action("deleteBillingReceipt");
        var bill = writable(id);
        var key = new ReceiptKey(id, receiptId);
        var before = require(key);
        open(before.rcpt_dt());
        balance(bill, receiptId, 0);
        BillingRules.requireChanged(mapper.delete(key));
        audit(id, "DELETE", before, null);
        return detail(bill);
    }
    private ReceiptDetail detail(BillState bill) {
        return new ReceiptDetail(tree(mapper.billing(bill.bill_id())),
            trees(mapper.details(bill.bill_id())), mapper.receipts(bill.bill_id()),
            trees(mapper.amendments(bill.bill_id())), bill.orgnl_bill_id(),
            trees(mapper.history(bill.bill_id())), mapper.warnings(bill.bill_id()));
    }
    private List<JsonNode> trees(List<String> values) { return values.stream().map(this::tree).toList(); }
    private JsonNode tree(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("입금 조회를 처리할 수 없습니다.", ex); }
    }
    private String snapshot(ReceiptRow row) {
        if (row == null) return null;
        try { return json.writer().without(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(row); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("입금 기록을 저장할 수 없습니다.", ex); }
    }
    private void audit(long id, String type, ReceiptRow before, ReceiptRow after) {
        ReceiptRow row = after == null ? before : after;
        mapper.audit(new ReceiptAudit(Long.toString(row.receipt_id()), type, snapshot(before), snapshot(after),
            "청구 #" + id + " 입금", user.userId()));
    }
}
