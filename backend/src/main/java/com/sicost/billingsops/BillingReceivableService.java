package com.sicost.billingsops;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.validation.BillingRules;
import java.lang.reflect.RecordComponent;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
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
    public LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    public long received(long id) {
        return mapper.receipts(id).stream().mapToLong(Receipt::rcpt_amt)
            .reduce(0, (a, b) -> BillingRules.add(a, b, "received_amt"));
    }
    public long adjustment(long id) {
        return mapper.amendments(id).stream().filter(b -> "CONFIRM".equals(b.bill_stts_cd()))
            .mapToLong(Bill::tot_amt).reduce(0, (a, b) -> BillingRules.add(a, b, "adjusted_total_amt"));
    }
    public BillingRow row(Bill bill) {
        Project project = mapper.project(bill.prj_id());
        boolean root = "N".equals(bill.amnd_yn());
        long paid = root ? received(bill.bill_id()) : 0;
        long adjusted = BillingRules.add(bill.tot_amt(), root ? adjustment(bill.bill_id()) : 0, "adjusted_total_amt");
        long balance = root && "CONFIRM".equals(bill.bill_stts_cd()) ? adjusted - paid : 0;
        Integer stage = stage(bill.rcpt_plan_dt(), balance, bill.dun_stop_yn());
        return new BillingRow(bill.bill_id(), bill.bill_stg_id(), false, bill.bill_no(), bill.prj_id(),
            project.project_name(), project.customer_id(), project.customer_name(), project.contract_type(),
            bill.atrb_ym(), bill.bill_dt(), bill.sply_amt(), bill.vat_amt(), bill.tot_amt(), bill.bill_stts_cd(),
            bill.txinv_no(), bill.txinv_dt(), bill.rcpt_plan_dt(), paid, adjusted, balance, stage);
    }
    public Integer stage(LocalDate due, long balance, String stopped) {
        if (due == null || balance <= 0 || "Y".equals(stopped)) return null;
        long elapsed = ChronoUnit.DAYS.between(due, today());
        return elapsed >= 60 ? 3 : elapsed >= 30 ? 2 : elapsed >= 7 ? 1 : 0;
    }
    public LocalDate next(LocalDate due, long balance, String stopped) {
        if (due == null || balance <= 0 || "Y".equals(stopped)) return null;
        long elapsed = ChronoUnit.DAYS.between(due, today());
        return elapsed >= 60 ? null : due.plusDays(elapsed >= 30 ? 60 : elapsed >= 7 ? 30 : 7);
    }
    public List<OverdueRow> all(OverdueFilter filter) {
        if (filter.stage() != null && (filter.stage() < 0 || filter.stage() > 3)) throw BillingRules.invalid("stage");
        if (filter.customerId() != null && filter.customerId() < 1) throw BillingRules.invalid("customerId");
        if (filter.overDays() != null && !Set.of(30, 60, 90).contains(filter.overDays())) throw BillingRules.invalid("overDays");
        if (filter.year() != null && (filter.year() < 1 || filter.year() > 9999)) throw BillingRules.invalid("year");
        List<OverdueRow> result = new ArrayList<>();
        for (Bill bill : mapper.bills()) {
            if (!"CONFIRM".equals(bill.bill_stts_cd()) || !"N".equals(bill.amnd_yn())) continue;
            BillingRow row = row(bill);
            if (row.receivable_amt() <= 0) continue;
            YearMonth month = BillingRules.revenueMonth(row.contract_type(), row.atrb_ym(), row.bill_dt());
            if (filter.year() != null && month.getYear() != filter.year()) continue;
            if (filter.customerId() != null && !filter.customerId().equals(row.customer_id())) continue;
            Long days = row.rcpt_plan_dt() == null ? null : ChronoUnit.DAYS.between(row.rcpt_plan_dt(), today());
            if (filter.stage() != null && !filter.stage().equals(row.reminder_stage())) continue;
            if (filter.overDays() != null && (days == null || days <= filter.overDays())) continue;
            if (filter.q() != null && !(Objects.toString(row.project_name(), "") + " "
                + Objects.toString(row.customer_name(), "") + " " + row.bill_no()).toLowerCase(Locale.ROOT)
                .contains(filter.q().toLowerCase(Locale.ROOT))) continue;
            Part part = mapper.part(BillingAmountService.sourceKey(row.prj_id(), month.toString()));
            List<String> roles = new ArrayList<>();
            Integer stage = row.reminder_stage();
            if (stage != null && stage >= 1) { roles.add("영업 담당자"); roles.add("PM"); }
            if (stage != null && stage >= 2) roles.add(part != null && "Y".equals(part.hq_yn()) ? "본사 영업 책임자" : "영업 파트장");
            if (stage != null && stage >= 3) { roles.add("경영진"); roles.add("재무"); }
            result.add(new OverdueRow(row, days, stage,
                days == null ? "예정일 미입력" : days < 0 ? "예정" : days == 0 ? "당일" : "연체",
                roles, next(row.rcpt_plan_dt(), row.receivable_amt(), bill.dun_stop_yn()),
                mapper.sent(row.bill_id()), part == null ? null : part.sales_part()));
        }
        return result;
    }
    public OverdueResult overdue(OverdueFilter filter, BillingPaging paging) {
        List<OverdueRow> rows = all(filter);
        rows.sort(order(paging, r -> r, r -> r.billing().bill_id()));
        Map<Long, CustomerTotal> customers = new LinkedHashMap<>();
        for (OverdueRow r : rows) {
            var b = r.billing();
            var old = customers.get(b.customer_id());
            customers.put(b.customer_id(), new CustomerTotal(b.customer_id(), b.customer_name(),
                BillingRules.add(old == null ? 0 : old.receivable_amt(), b.receivable_amt(), "receivable_amt")));
        }
        List<StageCount> counts = new ArrayList<>();
        for (int stage = 0; stage <= 3; stage++) {
            int key = stage;
            counts.add(new StageCount(stage, rows.stream().filter(r -> Objects.equals(r.stage(), key)).count()));
        }
        return new OverdueResult(page(rows, paging), new ArrayList<>(customers.values()), counts,
            rows.size(), paging.page(), paging.size());
    }
    static <T> List<T> page(List<T> rows, BillingPaging paging) {
        int from = (int)Math.min(rows.size(), paging.offset());
        return List.copyOf(rows.subList(from, Math.min(rows.size(), from + paging.size())));
    }
    static Object value(Object record, String field) {
        if (record instanceof OverdueRow r) {
            try { return valueDirect(r, field); }
            catch (IllegalArgumentException ex) { return valueDirect(r.billing(), field); }
        }
        return valueDirect(record, field);
    }
    private static Object valueDirect(Object record, String field) {
        for (RecordComponent c : record.getClass().getRecordComponents()) {
            if (c.getName().equals(field)) {
                try { return c.getAccessor().invoke(record); }
                catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
            }
        }
        throw new IllegalArgumentException(field);
    }
    static <T> Comparator<T> order(BillingPaging paging, java.util.function.Function<T, Object> record,
        java.util.function.ToLongFunction<T> identity) {
        String[] sort = paging.sort().split(",");
        Comparator<T> comparator = (a, b) -> compare(value(record.apply(a), sort[0]), value(record.apply(b), sort[0]), sort[1]);
        return comparator.thenComparingLong(identity);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object a, Object b, String direction) {
        if (a == null) return b == null ? 0 : 1;
        if (b == null) return -1;
        int result = ((Comparable)a).compareTo(b);
        return direction.equals("desc") ? -result : result;
    }
}
