package com.sicost.receipts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.receipts.BillingReceiptModels.*;

@Service
@Transactional(readOnly = true)
public class BillingReceiptService {
    private final BillingReceiptMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;
    private final BillingReceivableService receivables;
    private final Clock clock;

    public BillingReceiptService(BillingReceiptMapper mapper, CurrentUser user,
            PermissionService permissions, ObjectMapper json, BillingReceivableService receivables, Clock clock) {
        this.mapper = mapper;
        this.user = user;
        this.permissions = permissions;
        this.json = json;
        this.receivables = receivables;
        this.clock = clock;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BillingDetail list(long id) {
        permissions.can(BillingActions.key("listBillingReceipts"));
        BillingReceiptInput.id(id, "id");
        return detail(id);
    }
    private Header require(long id) {
        var header = mapper.header(id);
        if (header == null) throw ApiException.notFound("청구가 없습니다.");
        return header;
    }
    private void lock(long id) {
        BillingReceiptInput.id(id, "id");
        var state = mapper.lockBill(id);
        if (state == null) throw ApiException.notFound("청구가 없습니다.");
        BillingRules.requireReceiptTarget(BillingRules.Status.valueOf(state.bill_stts_cd()),
            !"N".equals(state.amnd_yn()) || state.orgnl_bill_id() != null);
        BillingRules.requireBillable(require(id).contract_type());
    }
    private Receipt require(ReceiptKey key) {
        BillingReceiptInput.id(key.receiptId(), "receiptId");
        var row = mapper.row(key);
        if (row == null) throw ApiException.notFound("해당 청구의 입금이 없습니다.");
        return row;
    }
    private void open(LocalDate date) {
        var month = YearMonth.from(date);
        BillingRules.requireOpen(month, mapper.monthStatus(month.toString()), "rcpt_dt");
    }
    @Transactional
    public BillingDetail create(long id, ReceiptInput input) {
        permissions.can(BillingActions.key("createBillingReceipt"));
        BillingReceiptInput.validate(input, null);
        lock(id);
        open(input.rcpt_dt());
        var balance = receivables.balance(id);
        BillingRules.requireReceiptAmount(balance.adjusted_total_amt(), balance.received_amt(), input.rcpt_amt());
        long receiptId = mapper.insert(new ReceiptWrite(id, 0, input, user.userId()));
        var after = require(new ReceiptKey(id, receiptId));
        audit(id, receiptId, "INSERT", null, after);
        return detail(id);
    }
    @Transactional
    public BillingDetail update(long id, long receiptId, ReceiptInput input) {
        permissions.can(BillingActions.key("updateBillingReceipt"));
        BillingReceiptInput.id(receiptId, "receiptId");
        BillingReceiptInput.validate(input, receiptId);
        lock(id);
        var key = new ReceiptKey(id, receiptId);
        var before = require(key);
        open(before.rcpt_dt());
        open(input.rcpt_dt());
        var balance = receivables.balance(id);
        BillingRules.requireReceiptAmount(balance.adjusted_total_amt(),
            balance.received_amt() - before.rcpt_amt(), input.rcpt_amt());
        BillingRules.requireChanged(mapper.update(new ReceiptWrite(id, receiptId, input, user.userId())));
        audit(id, receiptId, "UPDATE", before, require(key));
        return detail(id);
    }
    @Transactional
    public BillingDetail delete(long id, long receiptId) {
        permissions.can(BillingActions.key("deleteBillingReceipt"));
        BillingReceiptInput.id(receiptId, "receiptId");
        lock(id);
        var key = new ReceiptKey(id, receiptId);
        var before = require(key);
        open(before.rcpt_dt());
        var balance = receivables.balance(id);
        if (balance.received_amt() - before.rcpt_amt() > balance.adjusted_total_amt())
            throw ApiException.conflict("OVER_RECEIPT", "입금 누계가 조정 후 청구 총액을 초과합니다.", "rcpt_amt");
        BillingRules.requireChanged(mapper.delete(key));
        audit(id, receiptId, "DELETE", before, null);
        return detail(id);
    }
    private BillingRow row(Header h) {
        var b = receivables.balance(h.bill_id());
        Integer stage = null;
        if (b.receivable_amt() > 0 && h.rcpt_plan_dt() != null) {
            long days = ChronoUnit.DAYS.between(h.rcpt_plan_dt(), ProjectValues.today(clock));
            stage = days >= 60 ? 3 : days >= 30 ? 2 : days >= 7 ? 1 : 0;
        }
        return new BillingRow(h.bill_id(), h.bill_stg_id(), false, h.bill_no(), h.prj_id(),
            h.project_name(), h.customer_id(), h.customer_name(), h.contract_type(), h.atrb_ym(),
            h.bill_dt(), h.sply_amt(), h.vat_amt(), h.tot_amt(), h.bill_stts_cd(), h.txinv_no(),
            h.txinv_dt(), h.rcpt_plan_dt(), b.received_amt(), b.adjusted_total_amt(), b.receivable_amt(), stage);
    }
    private BillingDetail detail(long id) {
        var h = require(id);
        var lines = mapper.details(id);
        BigDecimal sum = lines.stream().map(l -> BigDecimal.valueOf(l.bill_amt()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        var warnings = Stream.of("공급가액과 상세 합계가 다릅니다.")
            .filter(w -> !lines.isEmpty() && sum.compareTo(BigDecimal.valueOf(h.sply_amt())) != 0).toList();
        return new BillingDetail(row(h), lines, mapper.list(id),
            mapper.amendments(id).stream().map(this::row).toList(), h.orgnl_bill_id(), mapper.history(id), warnings);
    }
    private String snapshot(Receipt row) {
        if (row == null) return null;
        try { return json.writeValueAsString(row); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private void audit(long billId, long receiptId, String type, Receipt before, Receipt after) {
        mapper.audit(new ReceiptAudit(Long.toString(billId), Long.toString(receiptId), type,
            snapshot(before), snapshot(after), user.userId()));
    }
}
