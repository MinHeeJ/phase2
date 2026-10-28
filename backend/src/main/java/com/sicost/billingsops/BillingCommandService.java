package com.sicost.billingsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.validation.BillingRules;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service
@Transactional(readOnly = true)
public class BillingCommandService {
    private final BillingMapper mapper;
    private final BillingReceivableService receivables;
    private final CurrentUser user;
    private final Clock clock;
    private final ObjectMapper json;
    public BillingCommandService(BillingMapper mapper, BillingReceivableService receivables,
        CurrentUser user, Clock clock, ObjectMapper json) {
        this.mapper = mapper;
        this.receivables = receivables;
        this.user = user;
        this.clock = clock;
        this.json = json;
    }
    private Bill require(long id) {
        if (id < 1) throw BillingRules.invalid("id");
        Bill bill = mapper.bill(id);
        if (bill == null) throw ApiException.notFound("청구가 없습니다.");
        return bill;
    }
    private Bill locked(long id) {
        Bill observed = require(id);
        if (mapper.lockProject(observed.prj_id()) == null) throw ApiException.notFound("프로젝트가 없습니다.");
        if (observed.orgnl_bill_id() != null) mapper.lockBill(observed.orgnl_bill_id());
        Bill current = mapper.lockBill(id);
        if (current == null) throw ApiException.notFound("청구가 없습니다.");
        Project project = mapper.project(current.prj_id());
        BillingRules.contractType(project.contract_type());
        if (!"Y".equals(project.use_yn())) throw BillingRules.invalid("use_yn");
        return current;
    }
    private void open(Bill bill) {
        Project project = mapper.project(bill.prj_id());
        String month = BillingRules.revenueMonth(project.contract_type(), bill.atrb_ym(), bill.bill_dt()).toString();
        BillingRules.openMonth(month, mapper.monthStatus(month));
    }
    private void bounds(Bill root, long supply, long vat) {
        if (!"CONFIRM".equals(root.bill_stts_cd()) || !"N".equals(root.amnd_yn())) throw BillingRules.stale();
        long confirmedSupply = mapper.amendments(root.bill_id()).stream()
            .filter(b -> "CONFIRM".equals(b.bill_stts_cd())).mapToLong(Bill::sply_amt)
            .reduce(0, (a, b) -> BillingRules.add(a, b, "sply_amt"));
        long remainingSupply = BillingRules.add(BillingRules.add(root.sply_amt(), confirmedSupply, "sply_amt"), supply, "sply_amt");
        if (remainingSupply < 0) throw BillingRules.invalid("sply_amt");
        long total = BillingRules.add(BillingRules.add(root.tot_amt(), receivables.adjustment(root.bill_id()), "tot_amt"),
            BillingRules.total(supply, vat), "tot_amt");
        long received = receivables.received(root.bill_id());
        if (total < received) throw ApiException.conflict("REFUND_REQUIRED", "입금액보다 적게 감액할 수 없습니다.", "sply_amt");
    }
    @Transactional
    public BillingDetail amend(long id, Amendment input) {
        BillingRules.amounts(input.sply_amt(), input.vat_amt(), true, false);
        reason(input.reason());
        String generationMonth = YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
        mapper.numberLock(generationMonth);
        Bill root = locked(id);
        open(root);
        bounds(root, input.sply_amt(), input.vat_amt());
        if ("TK".equals(mapper.project(root.prj_id()).contract_type()) && input.bill_dt() != null) {
            String month = YearMonth.from(input.bill_dt()).toString();
            BillingRules.openMonth(month, mapper.monthStatus(month));
        }
        Long key = mapper.insertAmendment(new AmendmentWrite(id, mapper.nextNumber(generationMonth), input, user.userId()));
        if (key == null) throw BillingRules.stale();
        mapper.amendmentDetail(key);
        Bill saved = require(key);
        audit("INSERT", null, saved);
        return detail(saved);
    }
    @Transactional
    public BillingDetail confirm(long id) {
        Bill bill = locked(id);
        BillingRules.draft(bill.bill_stts_cd());
        if (bill.bill_dt() == null) throw BillingRules.invalid("bill_dt");
        boolean amendment = "Y".equals(bill.amnd_yn());
        BillingRules.amounts(bill.sply_amt(), bill.vat_amt(), amendment, true);
        open(bill);
        if (amendment) {
            if (bill.orgnl_bill_id() == null) throw BillingRules.stale();
            Bill root = mapper.lockBill(bill.orgnl_bill_id());
            if (root == null) throw BillingRules.stale();
            open(root);
            bounds(root, bill.sply_amt(), bill.vat_amt());
        } else {
            Project p = mapper.project(bill.prj_id());
            if ("DP".equals(p.contract_type()) && Set.of("DAY", "HOUR").contains(Objects.toString(p.settlement(), ""))) {
                var sources = mapper.sources(BillingAmountService.sourceKey(p.prj_id(), bill.atrb_ym()));
                for (DetailLine line : mapper.details(id)) {
                    if (line.asgn_id() == null) continue;
                    Source source = sources.stream().filter(s -> s.asgn_id() == line.asgn_id()).findFirst().orElse(null);
                    if (source == null || source.atnd_id() == null) throw BillingRules.invalid("details");
                }
            }
        }
        BillingRules.changed(mapper.state(new StateWrite(id, "DRAFT", "CONFIRM", null, user.userId())));
        Bill saved = require(id);
        audit("UPDATE", bill, saved);
        return detail(saved);
    }
    @Transactional
    public BillingDetail cancel(long id, String reason) {
        reason(reason);
        Bill bill = locked(id);
        if (!Set.of("PLAN", "DRAFT", "CONFIRM").contains(bill.bill_stts_cd())) throw BillingRules.stale();
        open(bill);
        if (!mapper.receipts(id).isEmpty()) throw BillingRules.stale();
        if (mapper.amendments(id).stream().anyMatch(b -> !"CANCEL".equals(b.bill_stts_cd()))) throw BillingRules.stale();
        if ("Y".equals(bill.amnd_yn()) && "CONFIRM".equals(bill.bill_stts_cd())) throw BillingRules.stale();
        BillingRules.changed(mapper.state(new StateWrite(id, bill.bill_stts_cd(), "CANCEL", reason, user.userId())));
        Bill saved = require(id);
        audit("UPDATE", bill, saved);
        return detail(saved);
    }
    static void reason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw BillingRules.invalid("reason");
    }
    BillingDetail detail(Bill bill) {
        var warnings = new ArrayList<String>();
        if (bill.txinv_no() == null || bill.txinv_no().isBlank()) warnings.add("세금계산서 번호 미입력");
        var details = mapper.details(bill.bill_id());
        long detailSum = details.stream().mapToLong(DetailLine::bill_amt)
            .reduce(0, (a, b) -> BillingRules.add(a, b, "sply_amt"));
        if (!details.isEmpty() && detailSum != bill.sply_amt()) warnings.add("공급가액과 상세합이 다릅니다.");
        return new BillingDetail(receivables.row(bill), details, mapper.receipts(bill.bill_id()),
            mapper.amendments(bill.bill_id()).stream().map(receivables::row).toList(), bill.orgnl_bill_id(),
            mapper.history(bill.bill_id()), warnings);
    }
    private String snapshot(Object object) {
        if (object == null) return null;
        try { return json.writeValueAsString(object); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("청구 변경 기록을 저장할 수 없습니다.", ex); }
    }
    private void audit(String type, Bill before, Bill after) {
        mapper.audit(new Audit(Long.toString(after.bill_id()), type, snapshot(before), snapshot(after), user.userId()));
    }
}
