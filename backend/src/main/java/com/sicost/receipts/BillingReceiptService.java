package com.sicost.receipts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.*;
import com.sicost.common.security.*;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.receipts.BillingReceiptModels.*;

@Service
@Transactional(readOnly = true)
public class BillingReceiptService {
    private final BillingReceiptMapper mapper;
    private final BillingMutationService mutations;
    private final BillingReceivableService receivables;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;

    public BillingReceiptService(BillingReceiptMapper mapper, BillingMutationService mutations,
            BillingReceivableService receivables, CurrentUser user, PermissionService permissions,
            ObjectMapper json) {
        this.mapper = mapper;
        this.mutations = mutations;
        this.receivables = receivables;
        this.user = user;
        this.permissions = permissions;
        this.json = json;
    }
    private void permission(String operation) { BillingActions.can(permissions, operation); }
    private ReceiptBillingDetail detail(long id) {
        BillingReceiptInput.id(id, "id");
        var billing = mapper.billing(id);
        if (billing == null) throw ApiException.notFound("청구가 없습니다.");
        var lines = mapper.details(id);
        var warnings = new ArrayList<String>();
        if (billing.txinv_no() == null || billing.txinv_no().isBlank()) warnings.add("세금계산서 번호가 없습니다.");
        if (!"TK".equals(billing.contract_type()) && !lines.isEmpty()) {
            long total = 0;
            for (var line : lines) total = BillingRules.add(total, line.bill_amt(), "sply_amt");
            if (total != billing.sply_amt()) warnings.add("상세 합계와 공급가액이 다릅니다.");
        }
        return new ReceiptBillingDetail(billing, lines, mapper.list(id), mapper.amendments(id),
            mapper.originalId(id), mapper.history(id), warnings);
    }
    public ReceiptBillingDetail list(long id) {
        permission("listBillingReceipts");
        return detail(id);
    }
    private Balance lock(long id) {
        var state = mutations.lock(id);
        if (!"CONFIRM".equals(state.bill_stts_cd()) || state.orgnl_bill_id() != null) {
            throw BillingRules.stale();
        }
        var balance = receivables.balance(id);
        if (!"CONFIRM".equals(balance.bill_stts_cd()) || !"N".equals(balance.amnd_yn())) {
            throw BillingRules.stale();
        }
        return balance;
    }
    private ReceiptRow require(ReceiptKey key) {
        BillingReceiptInput.id(key.receiptId(), "receiptId");
        var row = mapper.row(key);
        if (row == null) throw ApiException.notFound("해당 청구의 입금이 없습니다.");
        return row;
    }
    private void validate(ReceiptInput input) {
        if (input == null || input.rcpt_dt() == null) throw BillingRules.invalid("rcpt_dt");
        if (input.rcpt_amt() < 1) throw BillingRules.invalid("rcpt_amt");
        BillingRules.text(input.rmrk_desc(), 200, "rmrk_desc");
    }
    @Transactional
    public ReceiptBillingDetail create(long id, ReceiptInput input) {
        permission("createBillingReceipt");
        validate(input);
        var balance = lock(id);
        mutations.requireReceiptMonths(null, input.rcpt_dt());
        receivables.requireReceipt(balance, input.rcpt_amt(), 0);
        Long receiptId = mapper.insert(new ReceiptWrite(id, null, input, user.userId()));
        var saved = require(new ReceiptKey(id, receiptId));
        audit(id, receiptId, "INSERT", null, saved);
        return detail(id);
    }
    @Transactional
    public ReceiptBillingDetail update(long id, long receiptId, ReceiptInput input) {
        permission("updateBillingReceipt");
        validate(input);
        BillingReceiptInput.id(receiptId, "receiptId");
        var balance = lock(id);
        var before = require(new ReceiptKey(id, receiptId));
        mutations.requireReceiptMonths(before.rcpt_dt(), input.rcpt_dt());
        receivables.requireReceipt(balance, input.rcpt_amt(), before.rcpt_amt());
        BillingRules.requireAffected(mapper.update(new ReceiptWrite(id, receiptId, input, user.userId())));
        audit(id, receiptId, "UPDATE", before, require(new ReceiptKey(id, receiptId)));
        return detail(id);
    }
    @Transactional
    public ReceiptBillingDetail delete(long id, long receiptId) {
        permission("deleteBillingReceipt");
        BillingReceiptInput.id(receiptId, "receiptId");
        var balance = lock(id);
        var key = new ReceiptKey(id, receiptId);
        var before = require(key);
        mutations.requireReceiptMonths(before.rcpt_dt(), null);
        long remaining = BillingRules.subtract(balance.received_amt(), before.rcpt_amt(), "rcpt_amt");
        if (remaining < 0 || remaining > balance.adjustedTotal()) {
            throw ApiException.conflict("OVER_RECEIPT", "입금 합계와 조정 청구 총액을 확인하세요.", "rcpt_amt");
        }
        BillingRules.requireAffected(mapper.delete(key));
        audit(id, receiptId, "DELETE", before, null);
        return detail(id);
    }
    private String snapshot(ReceiptRow row) {
        if (row == null) return null;
        try { return json.writeValueAsString(row); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("입금 이력을 저장할 수 없습니다.", ex); }
    }
    private void audit(long billId, long receiptId, String type, ReceiptRow before, ReceiptRow after) {
        mutations.audit(new Audit("bill.rcpt", Long.toString(receiptId), type, "receipt",
            snapshot(before), snapshot(after), "청구 입금 #" + billId, null));
    }
}
