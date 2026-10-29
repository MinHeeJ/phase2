package com.sicost.parts;

import com.sicost.common.costallocation.CostAllocationModels.Allocation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import static com.sicost.parts.PartSettlementModels.*;

public final class PartSettlementCalculator {
    private PartSettlementCalculator() {}
    public static List<Person> people(Key key, Standard standard, List<Salary> salaries,
        List<Allocation> allocations, Set<String> warnings) {
        var result = new ArrayList<Person>();
        for (var salary : salaries) {
            var rows = allocations.stream().filter(a -> a.dev_id() == salary.dev_id()).toList();
            BigDecimal mm = rows.stream().filter(a -> a.asgn_id() != null).map(Allocation::mm_qty)
                .filter(Objects::nonNull).reduce(new BigDecimal("0.00"), BigDecimal::add);
            Long revenue = rows.stream().anyMatch(a -> a.sls_amt() == null && !"RND".equals(a.aloc_ty_cd()))
                ? null : rows.stream().map(Allocation::sls_amt).filter(Objects::nonNull)
                    .reduce(0L, Math::addExact);
            if (mm.signum() == 0) warnings.add("MM 없음: " + salary.dev_id());
            if (revenue == null) warnings.add("단가 또는 배분표 없음: " + salary.dev_id());
            long pay = prorated(salary.slry_amt(), salary.first_start(), YearMonth.parse(key.month()));
            Long fee = "OS".equals(salary.emp_se_cd()) ? Long.valueOf(0)
                : standard == null ? null : standard.bsn_fee_amt();
            long retirement = "FT".equals(salary.emp_se_cd()) ? salary.rtrpay_acml_amt() : 0;
            Long cost = fee == null ? null : Math.addExact(Math.addExact(pay, fee), retirement);
            Long difference = revenue == null || cost == null ? null : Math.subtractExact(revenue, cost);
            Long profit = difference == null || standard == null ? null
                : BigDecimal.valueOf(difference).multiply(standard.prft_dstrb_rt())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValueExact();
            result.add(new Person(salary.dev_id(), salary.dev_nm(), salary.emp_se_cd(), salary.projects(),
                mm, revenue, pay, fee, retirement, cost, difference, profit,
                "OS".equals(salary.emp_se_cd()) ? "영업비 제외" : "FT".equals(salary.emp_se_cd())
                    ? "영업비+퇴직금" : "영업비"));
        }
        return List.copyOf(result);
    }
    public static long prorated(long salary, java.time.LocalDate first, YearMonth month) {
        if (first == null || !YearMonth.from(first).equals(month)) return salary;
        long days = ChronoUnit.DAYS.between(first, month.atEndOfMonth()) + 1;
        return BigDecimal.valueOf(salary).multiply(BigDecimal.valueOf(days))
            .divide(BigDecimal.valueOf(month.lengthOfMonth()), 0, RoundingMode.DOWN).longValueExact();
    }
    public static Long sum(List<Person> people, Function<Person, Long> field) {
        if (people.stream().anyMatch(p -> field.apply(p) == null)) return null;
        return people.stream().map(field).reduce(0L, Math::addExact);
    }
    public static Header header(Key key, Header saved, Standard standard, List<Person> people) {
        long card = saved == null ? 0 : saved.corp_card_amt();
        Long profit = standard == null ? null : sum(people, Person::prft_amt);
        return new Header(saved == null ? null : saved.stlm_id(), key.partId(), key.month(),
            standard == null ? null : standard.part_std_hist_id(),
            standard == null ? null : standard.prft_dstrb_rt(),
            standard == null ? null : standard.bsn_fee_amt(), people.size(),
            sum(people, Person::mth_untprc_amt), sum(people, Person::tot_exp_amt),
            sum(people, Person::diff_amt), profit, card,
            profit == null ? null : Math.subtractExact(profit, card), "DRAFT", null);
    }
}
