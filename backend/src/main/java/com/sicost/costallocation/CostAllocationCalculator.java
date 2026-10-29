package com.sicost.costallocation;

import com.sicost.common.validation.BillingValues;
import com.sicost.common.validation.CostRemainderAllocator;
import com.sicost.common.validation.CostRemainderAllocator.Weight;
import com.sicost.common.validation.CostValues;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import static com.sicost.costallocation.CostAllocationModels.*;

public final class CostAllocationCalculator {
    private static final BigDecimal ZERO_MM = new BigDecimal("0.00");
    private CostAllocationCalculator() {}

    public static Calculation calculate(String month, Sources sources) {
        var people = new TreeMap<Long, Person>();
        for (Person person : sources.people()) unique(people, person.dev_id(), person);
        var assignments = new TreeMap<Long, Assignment>();
        for (Assignment assignment : sources.assignments()) {
            if (!"CANCELLED".equals(assignment.asgn_stts_cd())) unique(assignments, assignment.asgn_id(), assignment);
        }
        var expenses = new TreeMap<Long, Expense>();
        for (Expense expense : sources.expenses()) unique(expenses, expense.expns_id(), expense);
        var warnings = new LinkedHashSet<String>();
        var rows = new ArrayList<AllocationRow>();
        long unallocated = 0;
        for (Person person : people.values()) {
            var active = assignments.values().stream().filter(a -> a.dev_id() == person.dev_id()).toList();
            BigDecimal total = active.stream().map(a -> mm(a.mm_qty())).reduce(ZERO_MM, BigDecimal::add);
            boolean indirect = "INDIRECT".equals(person.duty_se_cd());
            boolean employee = "FT".equals(person.emp_se_cd()) || "CT".equals(person.emp_se_cd());
            if (person.indv_lbr_cst_amt() == null) warnings.add("급여 미업로드: dev_id=" + person.dev_id());
            long labor = person.indv_lbr_cst_amt() == null ? 0 : person.indv_lbr_cst_amt();
            if (labor < 0) throw BillingValues.invalid("indv_lbr_cst_amt");
            if (total.compareTo(BigDecimal.ONE) > 0) warnings.add("1.0 초과: dev_id=" + person.dev_id());
            var personExpenses = expenses.values().stream().filter(e -> Objects.equals(e.dev_id(), person.dev_id())).toList();
            if (indirect) {
                long expense = 0;
                for (Expense e : personExpenses) expense = Math.addExact(expense, e.expns_amt());
                rows.add(new AllocationRow(month, person.dev_id(), null, null, "INDIRECT", ZERO_MM, total,
                    BigDecimal.ONE, labor, expense, 0, null, yn(total.compareTo(BigDecimal.ONE) > 0), "N", "N"));
                continue;
            }
            var weights = new ArrayList<Weight>();
            for (Assignment a : active) weights.add(new Weight(a.asgn_id(), mm(a.mm_qty())));
            BigDecimal denominator = employee ? total.max(BigDecimal.ONE) : total;
            BigDecimal idle = employee ? BigDecimal.ONE.subtract(total).max(BigDecimal.ZERO) : BigDecimal.ZERO;
            if (idle.signum() > 0) weights.add(new Weight(null, idle));
            if (denominator.signum() == 0) {
                long missing = labor;
                for (Expense e : personExpenses) missing = Math.addExact(missing, e.expns_amt());
                unallocated = Math.addExact(unallocated, missing);
                warnings.add("MM 없음·미배분 원가: dev_id=" + person.dev_id());
                continue;
            }
            var allocatedLabor = CostRemainderAllocator.allocate(labor, weights);
            var allocatedExpense = new long[weights.size()];
            for (Expense expense : personExpenses) {
                var shares = CostRemainderAllocator.allocate(expense.expns_amt(), weights);
                for (int i = 0; i < shares.size(); i++) allocatedExpense[i] = Math.addExact(allocatedExpense[i], shares.get(i));
            }
            Map<Long, Long> referenceRevenue = referenceRevenue(active, sources.turnkey(), warnings);
            for (int i = 0; i < active.size(); i++) {
                Assignment a = active.get(i);
                BigDecimal sourceMm = mm(a.mm_qty());
                if (a.mm_qty() == null) warnings.add("MM 없음: asgn_id=" + a.asgn_id());
                boolean lost = "LOST".equals(a.prj_stts_cd());
                boolean internal = "IN".equals(a.cntrct_ty_cd());
                boolean free = "FREE".equals(a.asgn_se_cd());
                boolean plan = "PLAN".equals(a.prj_stts_cd());
                if (plan) warnings.add("미계약 투입: asgn_id=" + a.asgn_id());
                rows.add(new AllocationRow(month, person.dev_id(), a.asgn_id(), lost ? null : a.prj_id(),
                    lost ? "IDLE" : internal ? "RND" : "PROJECT", sourceMm, total,
                    sourceMm.divide(denominator, 12, RoundingMode.HALF_UP), allocatedLabor.get(i),
                    allocatedExpense[i], lost || internal || free ? 0 : referenceRevenue.getOrDefault(a.asgn_id(), 0L),
                    a.bill_untprc_amt(), yn(total.compareTo(BigDecimal.ONE) > 0), yn(free), yn(plan)));
            }
            if (idle.signum() > 0) {
                int index = weights.size() - 1;
                rows.add(new AllocationRow(month, person.dev_id(), null, null, "IDLE", idle.setScale(2), total,
                    idle, allocatedLabor.get(index), allocatedExpense[index], 0, null, "N", "N", "N"));
            }
        }
        for (Assignment a : assignments.values()) {
            if (!people.containsKey(a.dev_id())) throw new IllegalStateException("배분 인력 원천이 없습니다.");
        }
        for (Expense expense : expenses.values()) {
            if (expense.dev_id() != null) {
                if (!people.containsKey(expense.dev_id())) throw new IllegalStateException("경비 인력 원천이 없습니다.");
                continue;
            }
            boolean lost = "LOST".equals(expense.prj_stts_cd());
            rows.add(new AllocationRow(month, null, null, lost ? null : expense.prj_id(),
                lost ? "IDLE" : "IN".equals(expense.cntrct_ty_cd()) ? "RND" : "PROJECT",
                ZERO_MM, ZERO_MM, null, 0, expense.expns_amt(), 0, null, "N", "N",
                yn("PLAN".equals(expense.prj_stts_cd()))));
        }
        rows.sort(Comparator.comparing(AllocationRow::dev_id, Comparator.nullsLast(Long::compareTo))
            .thenComparing(AllocationRow::asgn_id, Comparator.nullsLast(Long::compareTo))
            .thenComparing(AllocationRow::prj_id, Comparator.nullsLast(Long::compareTo)));
        return new Calculation(List.copyOf(rows), List.copyOf(warnings), unallocated);
    }

    private static Map<Long, Long> referenceRevenue(List<Assignment> assignments, List<Turnkey> turnkey,
            LinkedHashSet<String> warnings) {
        var result = new LinkedHashMap<Long, Long>();
        var tkProjects = new LinkedHashSet<Long>();
        for (Assignment a : assignments) {
            if ("FREE".equals(a.asgn_se_cd()) || "IN".equals(a.cntrct_ty_cd()) || "LOST".equals(a.prj_stts_cd())) continue;
            if ("TK".equals(a.cntrct_ty_cd())) {
                tkProjects.add(a.prj_id());
            } else if (a.bill_untprc_amt() == null) {
                warnings.add("단가 미등록: asgn_id=" + a.asgn_id());
            } else {
                result.put(a.asgn_id(), CostValues.signedWon(BigDecimal.valueOf(a.bill_untprc_amt()).multiply(mm(a.mm_qty()))));
            }
        }
        for (long project : tkProjects) {
            var eligible = assignments.stream().filter(a -> a.prj_id() == project && !"FREE".equals(a.asgn_se_cd())).toList();
            long dev = eligible.get(0).dev_id();
            var source = turnkey.stream().filter(t -> t.prj_id() == project && t.dev_id() == dev).findFirst();
            if (source.isEmpty() || source.get().aloc_amt() == null) {
                warnings.add("턴키 배분표 없음: prj_id=" + project + ",dev_id=" + dev);
                continue;
            }
            var weights = eligible.stream().map(a -> new Weight(a.asgn_id(), mm(a.mm_qty()))).toList();
            if (weights.stream().allMatch(w -> w.weight().signum() == 0)) {
                warnings.add("MM 없음: prj_id=" + project + ",dev_id=" + dev);
                continue;
            }
            var shares = CostRemainderAllocator.allocate(source.get().aloc_amt(), weights);
            for (int i = 0; i < eligible.size(); i++) result.put(eligible.get(i).asgn_id(), shares.get(i));
        }
        return result;
    }

    private static BigDecimal mm(BigDecimal value) {
        if (value == null) return ZERO_MM;
        if (value.signum() < 0) throw BillingValues.invalid("mm_qty");
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw BillingValues.invalid("mm_qty");
        }
    }

    private static String yn(boolean value) { return value ? "Y" : "N"; }

    private static <K, V> void unique(Map<K, V> values, K id, V value) {
        V before = values.putIfAbsent(id, value);
        if (before != null && !before.equals(value)) throw new IllegalStateException("배분 원천 식별자가 중복됩니다.");
    }
}
