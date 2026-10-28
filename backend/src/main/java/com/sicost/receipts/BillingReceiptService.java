package com.sicost.receipts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.BillingRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.receipts.BillingReceiptModels.*;

@Service
@Transactional(readOnly = true)
public class BillingReceiptService {
    private final BillingReceiptMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;

    public BillingReceiptService(BillingReceiptMapper mapper, CurrentUser user,
            PermissionService permissions, ObjectMapper json) {
        this.mapper = mapper;
        this.user = user;
        this.permissions = permissions;
        this.json = json;
    }
    private Root require(long id) {
        BillingReceiptInput.id(id, "id");
        var root = mapper.root(id);
        if (root == null) throw BillingRules.invalid("id");
        return root;
    }
    private Root writable(long id) {
        var initial = require(id);
        if (mapper.lockProject(initial.prj_id()) == null) throw BillingRules.stale();
        var root = mapper.lockRoot(id);
        if (root == null || root.prj_id() != initial.prj_id()
                || !"CONFIRM".equals(root.bill_stts_cd()) || !"N".equals(root.amnd_yn())
                || root.orgnl_bill_id() != null) throw BillingRules.stale();
        return root;
    }
    private Receipt require(ReceiptKey key) {
        BillingReceiptInput.id(key.receiptId(), "receiptId");
        var row = mapper.receipt(key);
        if (row == null) throw BillingRules.invalid("receiptId");
        return row;
    }
    private void open(LocalDate date) {
        if (date == null) throw BillingRules.invalid("rcpt_dt");
        String month = YearMonth.from(date).toString();
        BillingRules.openMonth(month, mapper.monthStatus(month));
    }
    private long amount(Map<String, Object> row, String field) {
        try { return new BigDecimal(row.get(field).toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException ex) { throw BillingRules.invalid(field); }
    }
    private void balance(long id, long removed, long requested) {
        var row = mapper.billing(id);
        long received = amount(row, "received_amt");
        long adjusted = amount(row, "adjusted_total_amt");
        long retained = BillingRules.add(received, -removed, "rcpt_amt");
        if (retained < 0 || adjusted < retained) {
            throw ApiException.conflict("OVER_RECEIPT", "조정 총액을 초과한 입금입니다.", "rcpt_amt");
        }
        if (requested > 0) BillingRules.receipt(requested, adjusted - retained);
    }
    public ReceiptDetail listBillingReceipts(long id) {
        BillingActions.can(permissions, "listBillingReceipts");
        var root = require(id);
        return detail(root);
    }
    private ReceiptDetail detail(Root root) {
        var billing = mapper.billing(root.bill_id());
        var warnings = new ArrayList<String>();
        if ("CONFIRM".equals(root.bill_stts_cd()) && billing.get("txinv_no") == null) {
            warnings.add("세금계산서 번호가 입력되지 않았습니다.");
        }
        return new ReceiptDetail(billing, mapper.details(root.bill_id()), mapper.receipts(root.bill_id()),
            mapper.amendments(root.bill_id()), root.orgnl_bill_id(), mapper.history(root.bill_id()), warnings);
    }
    @Transactional
    public ReceiptDetail createBillingReceipt(long id, ReceiptInput input) {
        BillingActions.can(permissions, "createBillingReceipt");
        var root = writable(id);
        open(input.date());
        if (input.amount() == null) throw BillingRules.invalid("rcpt_amt");
        BillingRules.receipt(input.amount(), Long.MAX_VALUE);
        balance(id, 0, input.amount());
        var row = new Receipt(null, input.date(), input.amount(), input.note());
        Long key = mapper.insert(new ReceiptWrite(id, row, user.userId()));
        var saved = require(new ReceiptKey(id, key));
        audit(id, "INSERT", null, saved);
        return detail(root);
    }
    @Transactional
    public ReceiptDetail updateBillingReceipt(long id, long receiptId, ReceiptInput input) {
        BillingActions.can(permissions, "updateBillingReceipt");
        BillingReceiptInput.id(receiptId, "receiptId");
        var root = writable(id);
        var before = require(new ReceiptKey(id, receiptId));
        var after = new Receipt(receiptId, input.date() == null ? before.rcpt_dt() : input.date(),
            input.amount() == null ? before.rcpt_amt() : input.amount(),
            input.notePresent() ? input.note() : before.rmrk_desc());
        open(before.rcpt_dt());
        open(after.rcpt_dt());
        BillingRules.receipt(after.rcpt_amt(), Long.MAX_VALUE);
        balance(id, before.rcpt_amt(), after.rcpt_amt());
        BillingRules.changed(mapper.update(new ReceiptWrite(id, after, user.userId())));
        audit(id, "UPDATE", before, after);
        return detail(root);
    }
    @Transactional
    public ReceiptDetail deleteBillingReceipt(long id, long receiptId) {
        BillingActions.can(permissions, "deleteBillingReceipt");
        BillingReceiptInput.id(receiptId, "receiptId");
        var root = writable(id);
        var key = new ReceiptKey(id, receiptId);
        var before = require(key);
        open(before.rcpt_dt());
        balance(id, before.rcpt_amt(), 0);
        BillingRules.changed(mapper.delete(key));
        audit(id, "DELETE", before, null);
        return detail(root);
    }
    private String snapshot(long id, Receipt receipt) {
        if (receipt == null) return null;
        var values = new LinkedHashMap<String, Object>();
        values.put("bill_id", id);
        values.put("receipt_id", receipt.receipt_id());
        values.put("rcpt_dt", receipt.rcpt_dt());
        values.put("rcpt_amt", receipt.rcpt_amt());
        values.put("rmrk_desc", receipt.rmrk_desc());
        try { return json.writeValueAsString(values); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("입금 이력을 저장할 수 없습니다.", ex); }
    }
    private void audit(long id, String type, Receipt before, Receipt after) {
        var row = after == null ? before : after;
        mapper.audit(new ReceiptAudit(row.receipt_id().toString(), type,
            snapshot(id, before), snapshot(id, after), user.userId()));
    }
}
