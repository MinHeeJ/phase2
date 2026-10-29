package com.sicost.common.costs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.sicost.common.costs.CostAllocationModels.*;

public final class CostAllocationEngine {
    private static final BigDecimal ZERO_MM = new BigDecimal("0.00");
    private record Share(Long id, BigDecimal weight) {}

    // Compare exact remainder numerators over a common denominator, never rounded ratios.
    private Map<Long, Long> split(long amount, List<Share> shares, BigDecimal denominator) {
        Map<Long, Long> result = new HashMap<>();
        Map<Long, BigDecimal> fractions = new HashMap<>();
        long used = 0;
        for (var share : shares) {
            BigDecimal numerator = BigDecimal.valueOf(amount).multiply(share.weight());
            long floor = numerator.divide(denominator, 0, RoundingMode.FLOOR).longValueExact();
            result.put(share.id(), floor);
            fractions.put(share.id(), numerator.subtract(denominator.multiply(BigDecimal.valueOf(floor))));
            used = Math.addExact(used, floor);
        }
        var order = new ArrayList<>(shares);
        order.sort(Comparator.<Share, BigDecimal>comparing(s -> fractions.get(s.id())).reversed()
            .thenComparing(Share::id, Comparator.nullsLast(Comparator.naturalOrder())));
        long remaining = Math.subtractExact(amount, used);
        for (int i = 0; i < remaining; i++) {
            Long id = order.get(i).id();
            result.put(id, Math.addExact(result.get(id), 1));
        }
        return result;
    }

    public Result calculate(String month, List<Salary> salaries, List<Assignment> assignments,
            List<Expense> expenses, List<Turnkey> turnkey) {
        List<Allocation> rows = new ArrayList<>();
        List<ProjectExpense> projectExpenses = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        List<Unallocated> unallocated = new ArrayList<>();
        Map<Long, Expense> uniqueExpenses = new LinkedHashMap<>();
        for (var expense : expenses) uniqueExpenses.putIfAbsent(expense.expns_id(), expense);
        for (var expense : uniqueExpenses.values()) {
            if (expense.dev_id() == null) {
                projectExpenses.add(new ProjectExpense(expense.expns_id(), expense.prj_id(),
                    "IN".equals(expense.cntrct_ty_cd()) ? "RND" : "PROJECT", expense.expns_amt()));
            }
        }
        for (var salary : salaries) {
            long dev = salary.dev_id();
            var person = assignments.stream().filter(a -> a.dev_id() == dev)
                .sorted(Comparator.comparingLong(Assignment::asgn_id)).toList();
            BigDecimal total = person.stream().map(Assignment::mm_qty).reduce(ZERO_MM, BigDecimal::add);
            var personalExpenses = uniqueExpenses.values().stream()
                .filter(e -> Objects.equals(e.dev_id(), dev)).toList();
            if (salary.indv_lbr_cst_amt() == null) {
                warnings.add(new Warning(dev, null, "SALARY_MISSING", "급여 미업로드"));
            }
            long labor = salary.indv_lbr_cst_amt() == null ? 0 : salary.indv_lbr_cst_amt();
            boolean indirect = "INDIRECT".equals(salary.duty_se_cd());
            boolean employed = Set.of("FT", "CT").contains(salary.emp_se_cd());
            if (indirect) {
                long expense = 0;
                for (var e : personalExpenses) expense = Math.addExact(expense, e.expns_amt());
                rows.add(new Allocation(dev, null, null, "INDIRECT", ZERO_MM, total, BigDecimal.ONE,
                    labor, expense, null, null, total.compareTo(BigDecimal.ONE) > 0, false, false));
                continue;
            }
            if (total.compareTo(BigDecimal.ONE) > 0) {
                warnings.add(new Warning(dev, null, "MM_OVER", "월 투입 MM 1.0 초과"));
            }
            if (total.signum() == 0) {
                warnings.add(new Warning(dev, null, "MM_MISSING", employed ? "MM 없음 — 대기 원가" : "MM 없음 — 미배분 원가"));
                if (!employed) {
                    // No fake project, IDLE classification or developer identity for unallocated FL/OS.
                    long expense = 0;
                    for (var e : personalExpenses) expense = Math.addExact(expense, e.expns_amt());
                    unallocated.add(new Unallocated(dev, labor, expense));
                    continue;
                }
            }
            BigDecimal denominator = employed ? total.max(BigDecimal.ONE) : total;
            List<Share> shares = new ArrayList<>();
            for (var a : person) if (a.mm_qty().signum() > 0) shares.add(new Share(a.asgn_id(), a.mm_qty()));
            if (employed && total.compareTo(BigDecimal.ONE) < 0) {
                shares.add(new Share(null, BigDecimal.ONE.subtract(total)));
            }
            var laborShares = split(labor, shares, denominator);
            Map<Long, Long> expenseShares = new HashMap<>();
            for (var expense : personalExpenses) {
                split(expense.expns_amt(), shares, denominator).forEach((id, value) ->
                    expenseShares.merge(id, value, Math::addExact));
            }
            Map<Long, Long> tkAmounts = new HashMap<>();
            for (var tk : turnkey) {
                if (tk.dev_id() != dev) continue;
                var candidates = person.stream().filter(a -> a.prj_id() == tk.prj_id()
                    && !"FREE".equals(a.asgn_se_cd()) && a.mm_qty().signum() > 0).toList();
                BigDecimal mm = candidates.stream().map(Assignment::mm_qty).reduce(ZERO_MM, BigDecimal::add);
                if (mm.signum() > 0) tkAmounts.putAll(split(tk.sls_amt(), candidates.stream()
                    .map(a -> new Share(a.asgn_id(), a.mm_qty())).toList(), mm));
            }
            for (var share : shares) {
                Assignment a = person.stream().filter(p -> Objects.equals(p.asgn_id(), share.id())).findFirst().orElse(null);
                boolean free = a != null && "FREE".equals(a.asgn_se_cd());
                boolean internal = a != null && "IN".equals(a.cntrct_ty_cd());
                boolean lost = a != null && "LOST".equals(a.prj_stts_cd());
                String type = a == null || lost ? "IDLE" : internal ? "RND" : "PROJECT";
                Long revenue = null;
                if (a != null && !internal) {
                    if (free) revenue = 0L;
                    else if ("TK".equals(a.cntrct_ty_cd())) revenue = tkAmounts.get(a.asgn_id());
                    else if (a.bill_untprc_amt() != null) revenue = BigDecimal.valueOf(a.bill_untprc_amt())
                        .multiply(a.mm_qty()).setScale(0, RoundingMode.FLOOR).longValueExact();
                    if (revenue == null) warnings.add(new Warning(dev, a.asgn_id(),
                        "TK".equals(a.cntrct_ty_cd()) ? "ALLOCATION_MISSING" : "PRICE_MISSING", "참고매출 근거 없음"));
                }
                rows.add(new Allocation(dev, a == null ? null : a.prj_id(), share.id(), type,
                    a == null ? ZERO_MM : a.mm_qty(), total,
                    share.weight().divide(denominator, 6, RoundingMode.HALF_UP),
                    laborShares.get(share.id()), expenseShares.getOrDefault(share.id(), 0L),
                    revenue, a == null ? null : a.bill_untprc_amt(), total.compareTo(BigDecimal.ONE) > 0,
                    free, a != null && "PLAN".equals(a.prj_stts_cd())));
            }
        }
        return new Result(month, false, List.copyOf(rows), List.copyOf(projectExpenses), List.copyOf(warnings),
            List.copyOf(unallocated));
    }
}
