package com.sicost.parts;

import com.sicost.common.validation.*;
import com.sicost.costallocation.CostAllocationModels.AllocationRow;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.sicost.parts.PartSettlementModels.*;

public final class PartSettlementCalculator {
    private PartSettlementCalculator() {}

    public static Standard effective(List<Standard> history, String month) {
        return history.stream().filter(s -> s.aply_bgn_ym().compareTo(month) <= 0
                && (s.aply_end_ym() == null || s.aply_end_ym().compareTo(month) >= 0))
            .max(Comparator.comparing(Standard::aply_bgn_ym)
                .thenComparingLong(Standard::part_std_hist_id)).orElse(null);
    }

    public static Item person(Person person, String month, Standard standard,
            List<AllocationRow> allocations, String projects) {
        var selected = allocations.stream().filter(a -> Objects.equals(a.dev_id(), person.dev_id())).toList();
        BigDecimal mm = selected.stream().filter(a -> a.asgn_id() != null)
            .map(AllocationRow::mm_qty).filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        long sales = selected.stream().mapToLong(AllocationRow::sls_amt).reduce(0, Math::addExact);
        long salary = salary(person, YearMonth.parse(month));
        long fee = standard == null || "OS".equals(person.emp_se_cd()) ? 0 : standard.bsn_fee_amt();
        long retirement = "FT".equals(person.emp_se_cd()) ? person.rtrpay_acml_amt() : 0;
        long expense = Math.addExact(Math.addExact(salary, fee), retirement);
        long difference = Math.subtractExact(sales, expense);
        long profit = standard == null ? 0 : CostValues.signedWon(BigDecimal.valueOf(difference)
            .multiply(standard.prft_dstrb_rt()).movePointLeft(2));
        return new Item(null, person.dev_id(), person.dev_nm(), person.emp_se_cd(), projects,
            mm, sales, salary, fee, retirement, expense, difference, profit,
            "FT".equals(person.emp_se_cd()) ? "영업비+퇴직금" : "OS".equals(person.emp_se_cd()) ? null : "영업비");
    }

    public static long salary(Person person, YearMonth month) {
        if (person.first_start() == null || !YearMonth.from(person.first_start()).equals(month)) {
            return person.real_pay_amt();
        }
        long days = month.lengthOfMonth() - person.first_start().getDayOfMonth() + 1L;
        return BigDecimal.valueOf(person.real_pay_amt()).multiply(BigDecimal.valueOf(days))
            .divide(BigDecimal.valueOf(month.lengthOfMonth()), 0, RoundingMode.DOWN).longValueExact();
    }

    public static Settlement summarize(Scope scope, Settlement stored, Standard standard, List<Item> items) {
        long sales = 0, expenses = 0, differences = 0, profits = 0;
        for (Item item : items) {
            sales = Math.addExact(sales, item.mth_untprc_amt());
            expenses = Math.addExact(expenses, item.tot_exp_amt());
            differences = Math.addExact(differences, item.diff_amt());
            profits = Math.addExact(profits, item.prft_amt());
        }
        long card = stored == null ? 0 : stored.corp_card_amt();
        return new Settlement(stored == null ? null : stored.stlm_id(), scope.partId(), scope.month(), "DRAFT",
            standard == null ? null : standard.part_std_hist_id(), standard == null ? null : standard.ldr_nm(),
            standard == null ? null : standard.prft_dstrb_rt(), standard == null ? null : standard.bsn_fee_amt(),
            items.size(), sales, expenses, differences, profits, card, Math.subtractExact(profits, card),
            stored == null ? null : stored.rmrk_desc(), stored == null ? null : stored.cnfrm_at());
    }

    public static List<Trend> trend(List<Detail> months) {
        Map<Long, String> people = new TreeMap<>();
        months.forEach(m -> m.items().forEach(i -> people.put(i.dev_id(), i.dev_nm())));
        List<Trend> result = new ArrayList<>();
        for (var person : people.entrySet()) {
            for (String metric : List.of("mm_qty", "mth_untprc_amt", "tot_exp_amt", "diff_amt", "prft_amt")) {
                List<TrendValue> values = new ArrayList<>();
                for (Detail month : months) {
                    Item item = month.items().stream().filter(i -> i.dev_id() == person.getKey()).findFirst().orElse(null);
                    BigDecimal value = item == null ? BigDecimal.ZERO : switch (metric) {
                        case "mm_qty" -> item.mm_qty();
                        case "mth_untprc_amt" -> BigDecimal.valueOf(item.mth_untprc_amt());
                        case "tot_exp_amt" -> BigDecimal.valueOf(item.tot_exp_amt());
                        case "diff_amt" -> BigDecimal.valueOf(item.diff_amt());
                        default -> BigDecimal.valueOf(item.prft_amt());
                    };
                    values.add(new TrendValue(month.calculation_month(), value, !month.provisional()));
                }
                result.add(new Trend(person.getKey(), person.getValue(), metric, List.copyOf(values)));
            }
        }
        return List.copyOf(result);
    }
}
