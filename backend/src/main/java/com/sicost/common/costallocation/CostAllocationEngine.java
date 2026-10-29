package com.sicost.common.costallocation;

import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.sicost.common.costallocation.CostAllocationModels.*;

@Component
public class CostAllocationEngine {
    private record Share(Long asgnId, Long projectId, String type, BigDecimal weight, Assignment assignment) {}
    private record Fraction(int index, BigDecimal remainder, Long id) {}

    public Calculation calculate(String month, List<Person> people, List<Assignment> assignments, List<Expense> expenses) {
        var warnings = new LinkedHashSet<String>();
        var persons = new TreeMap<Long, Person>();
        for (var p : people) unique(persons, p.dev_id(), p);
        var valid = new TreeMap<Long, Assignment>();
        for (var a : assignments) {
            if ("CANCELLED".equals(a.asgn_stts_cd())) continue;
            unique(valid, a.asgn_id(), a);
            if (a.mm_qty() == null) warnings.add("MM 없음: " + a.dev_id() + "/" + a.asgn_id());
            else if (a.mm_qty().signum() < 0 || a.mm_qty().scale() > 2) throw invalid("mm_qty");
        }
        var expenseIds = new TreeMap<Long, Expense>();
        for (var expense : expenses) unique(expenseIds, expense.expns_id(), expense);
        var rows = new ArrayList<Allocation>();
        var unallocated = new ArrayList<Unallocated>();
        for (Person person : persons.values()) {
            var active = valid.values().stream().filter(a -> a.dev_id() == person.dev_id())
                .filter(a -> a.mm_qty() != null && a.mm_qty().signum() > 0).toList();
            BigDecimal total = active.stream().map(Assignment::mm_qty).reduce(new BigDecimal("0.00"), BigDecimal::add);
            if (person.indv_lbr_cst_amt() == null) warnings.add("급여 미업로드: " + person.dev_id());
            boolean indirect = "INDIRECT".equals(person.duty_se_cd());
            boolean employee = Set.of("FT", "CT").contains(person.emp_se_cd());
            if (!indirect && total.signum() == 0) warnings.add("MM 없음: " + person.dev_id());
            if (!indirect && total.compareTo(BigDecimal.ONE) > 0) warnings.add("1.0 초과: " + person.dev_id());
            var personalExpenses = expenseIds.values().stream()
                .filter(e -> Objects.equals(e.dev_id(), person.dev_id())).toList();
            if (!indirect && !employee && total.signum() == 0) {
                long expense = 0;
                for (var e : personalExpenses) expense = add(expense, e.expns_amt());
                unallocated.add(new Unallocated(person.dev_id(), person.indv_lbr_cst_amt(), expense));
                warnings.add("미배분 원가: " + person.dev_id());
                continue;
            }
            var shares = new ArrayList<Share>();
            BigDecimal denominator = employee ? total.max(BigDecimal.ONE) : total;
            if (indirect) {
                shares.add(new Share(null, null, "INDIRECT", BigDecimal.ONE, null));
                denominator = BigDecimal.ONE;
            } else {
                for (var a : active) {
                    String type = "LOST".equals(a.prj_stts_cd()) ? "IDLE" : "IN".equals(a.cntrct_ty_cd()) ? "RND" : "PROJECT";
                    shares.add(new Share(a.asgn_id(), a.prj_id(), type, a.mm_qty(), a));
                    if ("PLAN".equals(a.prj_stts_cd())) warnings.add("미계약 투입: " + a.prj_id());
                }
                if (employee && total.compareTo(BigDecimal.ONE) < 0)
                    shares.add(new Share(null, null, "IDLE", BigDecimal.ONE.subtract(total), null));
            }
            long[] labor = person.indv_lbr_cst_amt() == null ? new long[shares.size()]
                : distribute(person.indv_lbr_cst_amt(), shares, denominator);
            long[] expense = new long[shares.size()];
            for (var source : personalExpenses) {
                long[] allocated = distribute(source.expns_amt(), shares, denominator);
                for (int i = 0; i < allocated.length; i++) expense[i] = add(expense[i], allocated[i]);
            }
            var revenue = indirect ? Map.<Long, Long>of() : revenue(active, warnings);
            for (int i = 0; i < shares.size(); i++) {
                var share = shares.get(i); var a = share.assignment();
                rows.add(new Allocation(month, person.dev_id(), share.projectId(), share.asgnId(), share.type(),
                    a == null ? new BigDecimal("0.00") : a.mm_qty(), total,
                    share.weight().divide(denominator, 6, RoundingMode.HALF_UP),
                    person.indv_lbr_cst_amt() == null ? null : labor[i], expense[i],
                    a == null ? Long.valueOf(0) : revenue.get(a.asgn_id()), a == null ? null : a.bill_untprc_amt(),
                    total.compareTo(BigDecimal.ONE) > 0 ? "Y" : "N",
                    a != null && "FREE".equals(a.asgn_se_cd()) ? "Y" : "N",
                    a != null && "PLAN".equals(a.prj_stts_cd()) ? "Y" : "N"));
            }
        }
        for (var a : valid.values()) if (!persons.containsKey(a.dev_id())) throw invalid("dev_id");
        var projectExpenses = new ArrayList<ProjectExpense>();
        for (var expense : expenseIds.values()) {
            if (expense.dev_id() != null) {
                if (!persons.containsKey(expense.dev_id())) throw invalid("dev_id");
            } else if (expense.prj_id() != null) {
                String type = "LOST".equals(expense.prj_stts_cd()) ? "IDLE" : "IN".equals(expense.cntrct_ty_cd()) ? "RND" : "PROJECT";
                projectExpenses.add(new ProjectExpense(expense.expns_id(), expense.prj_id(), type, expense.expns_amt()));
            } else throw invalid("expns_id");
        }
        return new Calculation(List.copyOf(rows), List.copyOf(projectExpenses), List.copyOf(unallocated), List.copyOf(warnings));
    }

    private static Map<Long, Long> revenue(List<Assignment> assignments, Set<String> warnings) {
        var result = new HashMap<Long, Long>();
        var turnkey = new TreeMap<Long, List<Assignment>>();
        for (var a : assignments) {
            if ("IN".equals(a.cntrct_ty_cd())) result.put(a.asgn_id(), null);
            else if ("FREE".equals(a.asgn_se_cd())) result.put(a.asgn_id(), 0L);
            else if ("TK".equals(a.cntrct_ty_cd())) turnkey.computeIfAbsent(a.prj_id(), id -> new ArrayList<>()).add(a);
            else if (a.bill_untprc_amt() == null) {
                warnings.add("단가 미등록: " + a.asgn_id()); result.put(a.asgn_id(), null);
            } else result.put(a.asgn_id(), BigDecimal.valueOf(a.bill_untprc_amt()).multiply(a.mm_qty()).setScale(0, RoundingMode.DOWN).longValueExact());
        }
        for (var group : turnkey.values()) {
            Long amount = group.get(0).tk_sls_amt();
            if (amount == null) {
                warnings.add("배분표 없음: " + group.get(0).dev_id() + "/" + group.get(0).prj_id());
                group.forEach(a -> result.put(a.asgn_id(), null));
            } else {
                var shares = group.stream().map(a -> new Share(a.asgn_id(), a.prj_id(), "PROJECT", a.mm_qty(), a)).toList();
                var total = group.stream().map(Assignment::mm_qty).reduce(BigDecimal.ZERO, BigDecimal::add);
                long[] amounts = distribute(amount, shares, total);
                for (int i = 0; i < group.size(); i++) result.put(group.get(i).asgn_id(), amounts[i]);
            }
        }
        return result;
    }

    private static long[] distribute(long amount, List<Share> shares, BigDecimal denominator) {
        BigDecimal absolute = BigDecimal.valueOf(amount).abs();
        var values = new BigDecimal[shares.size()];
        var fractions = new ArrayList<Fraction>();
        BigDecimal floorTotal = BigDecimal.ZERO;
        for (int i = 0; i < shares.size(); i++) {
            BigDecimal numerator = absolute.multiply(shares.get(i).weight());
            BigDecimal[] divided = numerator.divideAndRemainder(denominator);
            values[i] = divided[0].setScale(0, RoundingMode.DOWN);
            floorTotal = floorTotal.add(values[i]);
            fractions.add(new Fraction(i, divided[1], shares.get(i).asgnId()));
        }
        int residual = absolute.subtract(floorTotal).intValueExact();
        fractions.sort(Comparator.comparing(Fraction::remainder).reversed()
            .thenComparing(Fraction::id, Comparator.nullsLast(Comparator.naturalOrder())));
        for (int i = 0; i < residual; i++) {
            int index = fractions.get(i).index(); values[index] = values[index].add(BigDecimal.ONE);
        }
        long[] result = new long[shares.size()];
        for (int i = 0; i < result.length; i++) result[i] = (amount < 0 ? values[i].negate() : values[i]).longValueExact();
        return result;
    }

    private static <K, V> void unique(Map<K, V> target, K key, V value) {
        V old = target.putIfAbsent(key, value);
        if (old != null && !old.equals(value)) throw invalid("source");
    }
    private static long add(long a, long b) {
        try { return Math.addExact(a, b); } catch (ArithmeticException ex) { throw invalid("amount"); }
    }
    private static ApiException invalid(String field) {
        return new ApiException(400, "VALIDATION_ERROR", "원가 원천 자료를 확인하세요.", field);
    }
}
