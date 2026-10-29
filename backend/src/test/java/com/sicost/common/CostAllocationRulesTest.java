package com.sicost.common;

import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CostRemainderAllocator;
import com.sicost.common.validation.CostRemainderAllocator.Weight;
import com.sicost.common.validation.CostValues;
import com.sicost.costallocation.CostAllocationCalculator;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static com.sicost.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;

class CostAllocationRulesTest {
    static BigDecimal n(String value) { return new BigDecimal(value); }
    static Person person(String employee, String duty, Long amount) { return new Person(7, employee, duty, amount); }
    static Assignment assignment(long id, String mm, String type, String status, String kind) {
        return new Assignment(id, 7, id + 100, type, status, kind, "ACTIVE", mm == null ? null : n(mm), 1000L);
    }
    static Calculation calculate(Person person, List<Assignment> assignments, List<Expense> expenses, List<Turnkey> turnkey) {
        return CostAllocationCalculator.calculate("2026-10", new Sources(List.of(person), assignments, expenses, turnkey));
    }
    static Calculation calculate(Person person, List<Assignment> assignments) {
        return calculate(person, assignments, List.of(), List.of());
    }

    @Test void edgeOneAllocatesLargestFractionNotLastRow() {
        var result = calculate(person("FT", "DIRECT", 100L), List.of(
            assignment(2, "0.50", "MO", "RUN", "NEW"), assignment(1, "1.00", "MO", "RUN", "NEW")));
        assertThat(result.items()).extracting(AllocationRow::lbr_cst_amt).containsExactly(67L, 33L);
        assertThat(result.items()).extracting(AllocationRow::mm_ovr_yn).containsOnly("Y");
        assertThat(result.items()).extracting(AllocationRow::mm_qty).containsExactly(n("1.00"), n("0.50"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("1.0 초과"));
    }

    @Test void tiesUseAssignmentIdentityAndIdleLast() {
        assertThat(CostRemainderAllocator.allocate(1, List.of(new Weight(9L, n("0.5")), new Weight(2L, n("0.5")))))
            .containsExactly(0L, 1L);
        assertThat(CostRemainderAllocator.allocate(1, List.of(new Weight(null, n("0.5")), new Weight(9L, n("0.5")))))
            .containsExactly(0L, 1L);
    }

    @ParameterizedTest @CsvSource({"FT,40,60", "CT,40,60", "FL,100,0", "OS,100,0"})
    void edgeTwoEmployeeBoundary(String employee, long project, long idle) {
        var result = calculate(person(employee, "DIRECT", 100L), List.of(assignment(1, "0.40", "MO", "RUN", "NEW")));
        assertThat(result.items().get(0).lbr_cst_amt()).isEqualTo(project);
        assertThat(result.items().stream().filter(r -> r.aloc_ty_cd().equals("IDLE")).mapToLong(AllocationRow::lbr_cst_amt).sum())
            .isEqualTo(idle);
        assertThat(result.items().stream().mapToLong(AllocationRow::lbr_cst_amt).sum()).isEqualTo(100);
    }

    @Test void freelanceWithoutMmReportsUnallocatedAndNeverIdle() {
        var result = calculate(person("FL", "DIRECT", 100L), List.of(), List.of(new Expense(1, 7L, null, null, null, 21)), List.of());
        assertThat(result.items()).isEmpty();
        assertThat(result.unallocated_cost_amt()).isEqualTo(121);
        assertThat(result.warnings()).anyMatch(w -> w.contains("MM 없음"));
    }

    @Test void employeeWithoutAssignmentRetainsAllIdleCost() {
        var result = calculate(person("CT", "DIRECT", 100L), List.of());
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).aloc_ty_cd()).isEqualTo("IDLE");
        assertThat(result.items().get(0).lbr_cst_amt()).isEqualTo(100);
    }

    @Test void edgeThreeFreeMmParticipatesWithoutDoubleSalaryOrRevenue() {
        var result = calculate(person("FT", "DIRECT", 100L), List.of(
            assignment(1, "0.50", "MO", "RUN", "FREE"), assignment(2, "0.50", "DP", "RUN", "NEW")));
        assertThat(result.items()).extracting(AllocationRow::lbr_cst_amt).containsExactly(50L, 50L);
        assertThat(result.items()).extracting(AllocationRow::sls_amt).containsExactly(0L, 500L);
    }

    @Test void expensesAreDeduplicatedAndEachSourceGetsItsOwnRemainder() {
        var expense = new Expense(1, 7L, 101L, "MO", "RUN", 1);
        var result = calculate(person("FT", "DIRECT", 1L), List.of(
            assignment(1, "0.50", "MO", "RUN", "NEW"), assignment(2, "0.50", "MO", "RUN", "NEW")),
            List.of(expense, expense, new Expense(2, null, 103L, "MO", "RUN", 11)), List.of());
        assertThat(result.items()).extracting(AllocationRow::lbr_cst_amt).containsExactly(1L, 0L, 0L);
        assertThat(result.items()).extracting(AllocationRow::drct_expns_amt).containsExactly(1L, 0L, 11L);
        assertThat(result.items().get(2).dev_id()).isNull();
        assertThat(result.items().stream().mapToLong(AllocationRow::cost).sum()).isEqualTo(13);
    }

    @Test void indirectNeverLeaksToProjectOrIdle() {
        var result = calculate(person("FT", "INDIRECT", 100L), List.of(assignment(1, "0.40", "IN", "RUN", "NEW")),
            List.of(new Expense(1, 7L, 101L, "IN", "RUN", 21)), List.of());
        assertThat(result.items()).hasSize(1);
        var row = result.items().get(0);
        assertThat(row.aloc_ty_cd()).isEqualTo("INDIRECT");
        assertThat(row.prj_id()).isNull();
        assertThat(row.cost()).isEqualTo(121);
        assertThat(row.sls_amt()).isZero();
    }

    @Test void internalIsRndAndSuppressesRevenue() {
        var result = calculate(person("FL", "DIRECT", 100L), List.of(assignment(1, "0.40", "IN", "RUN", "NEW")));
        assertThat(result.items().get(0).aloc_ty_cd()).isEqualTo("RND");
        assertThat(result.items().get(0).cost()).isEqualTo(100);
        assertThat(result.items().get(0).sls_amt()).isZero();
    }

    @Test void plannedPrecontractCostMovesToIdleOnlyOnOpenRecalculationAfterLost() {
        var planned = calculate(person("FL", "DIRECT", 100L), List.of(assignment(1, "0.40", "MO", "PLAN", "NEW")));
        assertThat(planned.items().get(0).pre_cntrct_yn()).isEqualTo("Y");
        assertThat(planned.items().get(0).prj_id()).isEqualTo(101);
        assertThat(planned.warnings()).anyMatch(w -> w.contains("미계약 투입"));
        var lost = calculate(person("FL", "DIRECT", 100L), List.of(assignment(1, "0.40", "MO", "LOST", "NEW")));
        assertThat(lost.items().get(0).aloc_ty_cd()).isEqualTo("IDLE");
        assertThat(lost.items().get(0).prj_id()).isNull();
        assertThat(lost.items().get(0).sls_amt()).isZero();
    }

    @Test void cancelledAssignmentsNeverEnterDenominator() {
        var cancelled = new Assignment(2, 7, 102, "MO", "RUN", "NEW", "CANCELLED", n("1.00"), 1000L);
        var result = calculate(person("FT", "DIRECT", 100L), List.of(assignment(1, "1.00", "MO", "RUN", "NEW"), cancelled));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).lbr_cst_amt()).isEqualTo(100);
        assertThat(result.items().get(0).tot_mm_qty()).isEqualByComparingTo("1.00");
    }

    @Test void edgeFourMissingSalaryAndPriceAreExplicitNotGuessed() {
        var noPrice = new Assignment(1, 7, 101, "MO", "RUN", "NEW", "ACTIVE", n("1.00"), null);
        var result = calculate(person("FT", "DIRECT", null), List.of(noPrice));
        assertThat(result.items().get(0).cost()).isZero();
        assertThat(result.items().get(0).bill_untprc_amt()).isNull();
        assertThat(result.warnings()).anyMatch(w -> w.contains("급여 미업로드")).anyMatch(w -> w.contains("단가 미등록"));
        assertThat(CostValues.percent(BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
    }

    @Test void edgeFourMissingTurnkeyAllocationHasSeparateWarning() {
        var result = calculate(person("FT", "DIRECT", 100L), List.of(assignment(1, "1.00", "TK", "RUN", "NEW")));
        assertThat(result.warnings()).anyMatch(w -> w.contains("턴키 배분표 없음"));
        assertThat(result.items().get(0).sls_amt()).isZero();
    }

    @Test void turnkeyPersonRevenueIsConsumedOnceAcrossAssignmentsNotPerJoin() {
        var first = new Assignment(1, 7, 101, "TK", "RUN", "NEW", "ACTIVE", n("0.50"), null);
        var second = new Assignment(2, 7, 101, "TK", "RUN", "NEW", "ACTIVE", n("0.50"), null);
        var result = calculate(person("FT", "DIRECT", 100L), List.of(first, second), List.of(), List.of(new Turnkey(101, 7, 101L)));
        assertThat(result.items()).extracting(AllocationRow::sls_amt).containsExactly(51L, 50L);
    }

    @Test void missingMmHasWarningAndNeverInventsFullTime() {
        var result = calculate(person("FT", "DIRECT", 100L), List.of(assignment(1, null, "MO", "RUN", "NEW")));
        assertThat(result.items().get(0).lbr_cst_amt()).isZero();
        assertThat(result.warnings()).anyMatch(w -> w.contains("MM 없음"));
    }

    @Test void signedSettlementProfitTruncatesTowardZero() {
        assertThat(CostValues.signedWon(n("-1.99"))).isEqualTo(-1);
        assertThat(CostValues.signedWon(n("1.99"))).isEqualTo(1);
    }

    @Test void exactFractionsConserveEvenBeyondFloatingPointIntegerPrecision() {
        var weights = List.of(new Weight(3L, n("0.33")), new Weight(1L, n("0.33")), new Weight(2L, n("0.34")));
        for (long amount : new long[]{0, 1, 2, 100, 9007199254740993L, Long.MAX_VALUE}) {
            var shares = CostRemainderAllocator.allocate(amount, weights);
            assertThat(shares.stream().reduce(0L, Math::addExact)).isEqualTo(amount);
            assertThat(shares).allMatch(v -> v >= 0);
        }
    }

    @Test void invalidMmAndConflictingExpenseIdentityFailWithoutGuessing() {
        assertThatThrownBy(() -> calculate(person("FT", "DIRECT", 100L), List.of(assignment(1, "0.123", "MO", "RUN", "NEW"))))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> calculate(person("FT", "DIRECT", 100L), List.of(), List.of(
            new Expense(1, 7L, null, null, null, 1), new Expense(1, 7L, null, null, null, 2)), List.of()))
            .isInstanceOf(IllegalStateException.class);
    }
}
