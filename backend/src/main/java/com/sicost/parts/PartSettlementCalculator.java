package com.sicost.parts;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.sicost.parts.PartSettlementModels.*;
import com.sicost.common.costs.CostAllocationModels.Allocation;

public final class PartSettlementCalculator {
    private PartSettlementCalculator() {}
    public static Person person(YearMonth month, Salary source, Standard standard,
            List<Allocation> allocations, List<String> warnings) {
        var rows = allocations.stream().filter(a -> Objects.equals(a.dev_id(), source.dev_id())).toList();
        BigDecimal mm = rows.stream().map(Allocation::mm_qty).filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        boolean missingRevenue = rows.isEmpty() || rows.stream().anyMatch(a -> a.sls_amt() == null);
        if (rows.isEmpty()) warnings.add(source.dev_nm() + ": 월 MM 없음 — 정산 근거 점검 필요");
        if (missingRevenue) warnings.add(source.dev_nm() + ": 단가 또는 턴키 배분자료 없음");
        long sales = rows.stream().map(Allocation::sls_amt).filter(Objects::nonNull)
            .reduce(0L, Math::addExact);
        Long salary = null;
        if (source.first_start() == null || source.first_start().isAfter(month.atEndOfMonth())) {
            warnings.add(source.dev_nm() + ": 투입 시작일 근거 없음 — 급여 일할 계산 보류");
        } else {
            long paid = "OS".equals(source.emp_se_cd()) ? source.slry_amt() : source.real_pay_amt();
            long days = source.first_start().isBefore(month.atDay(1)) ? month.lengthOfMonth()
                : ChronoUnit.DAYS.between(source.first_start(), month.atEndOfMonth()) + 1;
            salary = BigDecimal.valueOf(paid).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(month.lengthOfMonth()), 0, RoundingMode.FLOOR).longValueExact();
        }
        long fee = standard == null || "OS".equals(source.emp_se_cd()) ? 0 : standard.bsn_fee_amt();
        long retirement = "FT".equals(source.emp_se_cd()) ? source.rtrpay_acml_amt() : 0;
        Long expenses = salary == null ? null : Math.addExact(salary, Math.addExact(fee, retirement));
        Long difference = expenses == null || missingRevenue ? null : Math.subtractExact(sales, expenses);
        Long profit = difference == null || standard == null ? null : BigDecimal.valueOf(difference)
            .multiply(standard.prft_dstrb_rt()).divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR).longValueExact();
        return new Person(source.dev_id(), source.dev_nm(), source.emp_se_cd(), mm, sales,
            salary, fee, retirement, expenses, difference, profit,
            "FT".equals(source.emp_se_cd()) ? "영업비+퇴직금" : "OS".equals(source.emp_se_cd()) ? "외주" : "영업비");
    }
    public static Long partProfit(List<Person> people, long card) {
        if (people.stream().anyMatch(p -> p.prft_amt() == null)) return null;
        return Math.subtractExact(people.stream().map(Person::prft_amt).reduce(0L, Math::addExact), card);
    }
    public static Snapshot snapshot(Key key, Standard standard, List<Person> people, long card, Long actor) {
        return new Snapshot(key, standard, people, card,
            people.stream().map(Person::mth_untprc_amt).reduce(0L, Math::addExact),
            people.stream().map(Person::tot_exp_amt).reduce(0L, Math::addExact),
            people.stream().map(Person::diff_amt).reduce(0L, Math::addExact),
            people.stream().map(Person::prft_amt).reduce(0L, Math::addExact), partProfit(people, card), actor);
    }
}
