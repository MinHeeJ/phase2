package com.sicost.billingsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service("billingOperationsService")
@Transactional(readOnly = true)
public class BillingService {
    private final BillingMapper mapper;
    private final BillingReceivableService receivables;
    private final CurrentUser user;
    private final Clock clock;
    private final ObjectMapper json;
    public BillingService(BillingMapper mapper, BillingReceivableService receivables,
            CurrentUser user, Clock clock, ObjectMapper json) {
        this.mapper = mapper;
        this.receivables = receivables;
        this.user = user;
        this.clock = clock;
        this.json = json;
    }
    private Bill require(long id) {
        if (id < 1) throw BillingValues.invalid("id");
        Bill bill = mapper.bill(id);
        if (bill == null) throw ApiException.notFound("청구가 없습니다.");
        return bill;
    }
    private Bill lock(long id) {
        Bill initial = require(id);
        mapper.lockProject(initial.prj_id());
        if (initial.orgnl_bill_id() != null) {
            Bill root = mapper.lockBill(initial.orgnl_bill_id());
            if (root == null) throw BillingRules.stale();
        }
        Bill locked = mapper.lockBill(id);
        if (locked == null) throw BillingRules.stale();
        return locked;
    }
    private void open(Bill bill) {
        BillingRules.requireBillOpen(new BillingRules.Period(bill.contract_type(), bill.atrb_ym(), bill.bill_dt()),
            mapper.closedMonths().stream().map(YearMonth::parse).collect(Collectors.toSet()));
    }
    public BillingDetail detail(long id) {
        Bill bill = require(id);
        var details = mapper.details(id);
        var warnings = new ArrayList<String>();
        if (bill.txinv_no() == null || bill.txinv_no().isBlank()) warnings.add("세금계산서 번호가 없습니다. 발행은 별도로 진행하세요.");
        if (bill.orgnl_bill_id() == null && bill.bill_stg_id() == null
                && BillingAmountService.sum(details.stream().map(DetailLine::bill_amt).toList()) != bill.sply_amt()) {
            warnings.add("원천 상세 합과 수동 공급가가 다릅니다. 확인 후 확정할 수 있습니다.");
        }
        var children = mapper.bills().stream().filter(b -> Objects.equals(b.orgnl_bill_id(), id)).map(receivables::row).toList();
        return new BillingDetail(receivables.row(bill), details, mapper.receipts(id), children,
            bill.orgnl_bill_id(), mapper.history(id), List.copyOf(warnings));
    }
    @Transactional
    public BillingDetail confirm(long id) {
        Bill bill = lock(id);
        BillingRules.requireDraft(bill.bill_stts_cd());
        BillingAmounts.requireConfirmation(new BillingAmounts.Amounts(bill.sply_amt(), bill.vat_amt()),
            bill.orgnl_bill_id() != null, bill.bill_dt());
        open(bill);
        if (bill.orgnl_bill_id() != null) {
            Bill root = require(bill.orgnl_bill_id());
            open(root);
            amendmentAllowed(root, bill.sply_amt(), bill.tot_amt());
        }
        BillingRules.requireChanged(mapper.status(new StatusWrite(id, bill.bill_stts_cd(), "CONFIRM", null, user.userId())));
        audit("UPDATE", bill, require(id), null);
        return detail(id);
    }
    @Transactional
    public BillingDetail cancel(long id, Cancellation input) {
        String reason = reason(input == null ? null : input.reason());
        Bill bill = lock(id);
        open(bill);
        Balance balance = mapper.balance(id);
        BillingRules.requireCancellable(bill.bill_stts_cd(), balance.receipt_count());
        if (balance.amendment_count() > 0) throw BillingRules.stale();
        if (bill.orgnl_bill_id() != null) {
            Bill root = require(bill.orgnl_bill_id());
            if ("CONFIRM".equals(bill.bill_stts_cd())) {
                BillingRules.requireOriginalConfirmed(root.bill_stts_cd(), root.orgnl_bill_id() != null);
            }
            open(root);
        }
        BillingRules.requireChanged(mapper.status(new StatusWrite(id, bill.bill_stts_cd(), "CANCEL", reason, user.userId())));
        audit("UPDATE", bill, require(id), reason);
        return detail(id);
    }
    @Transactional
    public BillingDetail amend(long id, Amendment input) {
        validate(input);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String prefix = "B-" + today.format(DateTimeFormatter.ofPattern("yyMM")) + "-";
        mapper.numberLock(prefix);
        Bill original = lock(id);
        BillingRules.requireOriginalConfirmed(original.bill_stts_cd(), original.orgnl_bill_id() != null);
        open(original);
        BillingRules.requireBillOpen(new BillingRules.Period(original.contract_type(), original.atrb_ym(), input.bill_dt()),
            mapper.closedMonths().stream().map(YearMonth::parse).collect(Collectors.toSet()));
        long supply = BillingAmounts.exact(input.sply_amt(), "sply_amt");
        long total = new BillingAmounts.Amounts(supply, BillingAmounts.exact(input.vat_amt(), "vat_amt")).total();
        amendmentAllowed(original, supply, total);
        String number = prefix + String.format(Locale.ROOT, "%02d", mapper.nextNumber(prefix));
        long created = mapper.amendment(new AmendmentWrite(number, original, input, user.userId()));
        String month = "TK".equals(original.contract_type())
            ? YearMonth.from(input.bill_dt() == null ? today : input.bill_dt()).toString() : original.atrb_ym();
        mapper.amendmentDetail(new AmendmentDetail(created, month, supply, input.reason().substring(0, Math.min(200, input.reason().length()))));
        audit("INSERT", null, require(created), input.reason());
        return detail(created);
    }
    private void amendmentAllowed(Bill original, long supply, long total) {
        BillingRules.requireOriginalConfirmed(original.bill_stts_cd(), original.orgnl_bill_id() != null);
        Balance balance = mapper.balance(original.bill_id());
        BigDecimal remainingSupply = BigDecimal.valueOf(original.sply_amt()).add(BigDecimal.valueOf(balance.amendment_supply()))
            .add(BigDecimal.valueOf(supply));
        if (remainingSupply.signum() < 0) throw BillingValues.invalid("sply_amt");
        long adjusted = BillingAmounts.adjustedTotal(original.tot_amt(), balance.amendment_total());
        BillingAmounts.requireRefundSafe(BillingAmounts.adjustedTotal(adjusted, total), balance.received());
    }
    static String reason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw BillingValues.invalid("reason");
        return reason.trim();
    }
    static void validate(Amendment input) {
        if (input == null) throw BillingValues.invalid(null);
        reason(input.reason());
        if (input.sply_amt() == null || input.vat_amt() == null) throw BillingValues.invalid("sply_amt");
        long supply = BillingAmounts.exact(input.sply_amt(), "sply_amt");
        long vat = BillingAmounts.exact(input.vat_amt(), "vat_amt");
        if (supply >= 0) throw BillingValues.invalid("sply_amt");
        if (vat > 0) throw BillingValues.invalid("vat_amt");
        new BillingAmounts.Amounts(supply, vat).total();
        if (input.txinv_no() != null && input.txinv_no().length() > 50) throw BillingValues.invalid("txinv_no");
    }
    private String snapshot(Bill bill) {
        if (bill == null) return null;
        try { return json.writeValueAsString(bill); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("청구 이력을 저장할 수 없습니다.", ex); }
    }
    private void audit(String type, Bill before, Bill after, String reason) {
        mapper.audit(new Audit(Long.toString(after.bill_id()), type, snapshot(before), snapshot(after), reason, user.userId()));
    }
}
