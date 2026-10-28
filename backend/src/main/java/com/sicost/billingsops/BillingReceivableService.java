package com.sicost.billingsops;

import com.sicost.common.validation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service
@Transactional(readOnly = true)
public class BillingReceivableService {
    private final BillingMapper mapper;
    private final Clock clock;
    public BillingReceivableService(BillingMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }
    public BillingRow row(Bill bill) {
        Balance balance = mapper.balance(bill.bill_id());
        boolean original = bill.orgnl_bill_id() == null;
        long adjusted = original ? BillingAmounts.adjustedTotal(bill.tot_amt(), balance.amendment_total())
            : bill.tot_amt();
        long outstanding = original ? BillingAmounts.receivable(bill.bill_stts_cd(), adjusted, balance.received()) : 0;
        return new BillingRow(bill.bill_id(), bill.bill_stg_id(), false, bill.bill_no(), bill.prj_id(),
            bill.project_name(), bill.customer_id(), bill.customer_name(), bill.contract_type(), bill.atrb_ym(),
            bill.bill_dt(), bill.sply_amt(), bill.vat_amt(), bill.tot_amt(), bill.bill_stts_cd(),
            bill.txinv_no(), bill.txinv_dt(), bill.rcpt_plan_dt(), balance.received(), adjusted,
            outstanding, outstanding > 0 ? stage(bill.rcpt_plan_dt(), today()) : null);
    }
    LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    public static Integer stage(LocalDate due, LocalDate today) {
        if (due == null) return null;
        long days = ChronoUnit.DAYS.between(due, today);
        return days >= 60 ? 3 : days >= 30 ? 2 : days >= 7 ? 1 : 0;
    }
    public static LocalDate nextDate(LocalDate due, LocalDate today, long balance) {
        if (due == null || balance <= 0) return null;
        long days = ChronoUnit.DAYS.between(due, today);
        return days >= 60 ? null : due.plusDays(days < 7 ? 7 : days < 30 ? 30 : 60);
    }
    public static List<String> roles(int stage, Part part) {
        var roles = new LinkedHashSet<String>();
        if (stage >= 1) {
            roles.add("영업 담당자");
            roles.add("PM");
        }
        if (stage >= 2) roles.add(part == null || part.sales_part() == null ? "경영진"
            : "Y".equals(part.hq_yn()) ? "본사 영업 책임자" : "영업 파트장");
        if (stage >= 3) {
            roles.add("경영진");
            roles.add("재무");
        }
        return List.copyOf(roles);
    }
    public List<OverdueRow> all(OverdueRequest request) {
        int year = BillingQuery.year(request.year(), clock);
        if (request.stage() != null && (request.stage() < 0 || request.stage() > 3)) throw BillingValues.invalid("stage");
        if (request.overDays() != null && !Set.of(30,60,90).contains(request.overDays())) throw BillingValues.invalid("overDays");
        if (request.customerId() != null && request.customerId() <= 0) throw BillingValues.invalid("customerId");
        List<OverdueRow> rows = new ArrayList<>();
        for (Bill bill : mapper.bills()) {
            if (!"CONFIRM".equals(bill.bill_stts_cd()) || bill.orgnl_bill_id() != null) continue;
            String month = month(bill);
            if (month == null || !month.startsWith(year + "-")) continue;
            BillingRow row = row(bill);
            if (row.receivable_amt() <= 0 || request.customerId() != null && !request.customerId().equals(row.customer_id())) continue;
            if (!BillingQuery.search(request.q(), row.bill_no(), row.project_name(), row.customer_name())) continue;
            Integer days = row.rcpt_plan_dt() == null ? null : (int)ChronoUnit.DAYS.between(row.rcpt_plan_dt(), today());
            Integer stage = row.reminder_stage();
            if (request.stage() != null && !request.stage().equals(stage)) continue;
            if (request.overDays() != null && (days == null || days <= request.overDays())) continue;
            Part part = mapper.part(new PartKey(row.prj_id(), month));
            rows.add(new OverdueRow(row, days, stage,
                days == null ? "예정일 미입력" : days < 0 ? "예정" : days == 0 ? "당일" : "연체",
                roles(stage == null ? 0 : stage, part), nextDate(row.rcpt_plan_dt(), today(), row.receivable_amt()),
                Collections.emptyList(), part == null ? null : part.sales_part()));
        }
        String sort = paging(request).sort();
        Map<String, Function<OverdueRow, ? extends Comparable<?>>> fields = Map.of(
            "bill_no", r -> r.billing().bill_no(), "project_name", r -> r.billing().project_name(),
            "customer_name", r -> r.billing().customer_name(), "receivable_amt", r -> r.billing().receivable_amt(),
            "rcpt_plan_dt", r -> r.billing().rcpt_plan_dt(), "overdue_days", OverdueRow::overdue_days,
            "stage", OverdueRow::stage);
        return BillingQuery.sort(rows, sort, fields, r -> r.billing().bill_id());
    }
    public OverdueResult list(OverdueRequest request) {
        var rows = all(request);
        var paging = paging(request);
        var customers = new LinkedHashMap<Long, List<OverdueRow>>();
        for (var row : rows) customers.computeIfAbsent(row.billing().customer_id(), key -> new ArrayList<>()).add(row);
        List<CustomerTotal> totals = customers.entrySet().stream().map(e -> new CustomerTotal(e.getKey(),
            e.getValue().get(0).billing().customer_name(), BillingAmountService.sum(e.getValue().stream()
                .map(r -> r.billing().receivable_amt()).toList()))).toList();
        var counts = new ArrayList<StageCount>();
        for (int s = 0; s <= 3; s++) {
            final int selected = s;
            counts.add(new StageCount(s, rows.stream().filter(r -> Objects.equals(r.stage(), selected)).count()));
        }
        return new OverdueResult(BillingQuery.page(rows, paging), totals, List.copyOf(counts),
            rows.size(), paging.page(), paging.size());
    }
    private com.sicost.common.api.DeveloperPaging paging(OverdueRequest request) {
        return BillingValues.paging(request.page(), request.size(), request.sort(), BillingQuery.sorts(
            "bill_no", "project_name", "customer_name", "receivable_amt", "rcpt_plan_dt", "overdue_days", "stage"), "bill_no,asc");
    }
    public static String month(Bill bill) {
        return "TK".equals(bill.contract_type()) ? bill.bill_dt() == null ? null
            : YearMonth.from(bill.bill_dt()).toString() : bill.atrb_ym();
    }
}
