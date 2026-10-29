package com.sicost.common;

import com.sicost.common.costallocation.CostAllocationEngine;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.sicost.common.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;

class CostAllocationEngineTest {
    final CostAllocationEngine engine = new CostAllocationEngine();
    static BigDecimal n(String value) { return new BigDecimal(value); }
    static Person person(String type, Long salary) { return new Person(7, type, "DIRECT", salary); }
    static Assignment assignment(long id, String mm) {
        return new Assignment(id, 7, id + 100, n(mm), "ACTIVE", "MO", "RUN", "NEW", 1000L, null);
    }
    Calculation calculate(Person person, List<Assignment> assignments, List<Expense> expenses) {
        return engine.calculate("2026-10", List.of(person), assignments, expenses);
    }
    @Test void excessMmUsesLargestRemainderNotLastRow() {
        var result = calculate(person("FT", 100L), List.of(assignment(20, "1.00"), assignment(10, "0.50")), List.of());
        assertThat(result.items()).extracting(Allocation::lbr_cst_amt).containsExactly(33L, 67L);
        assertThat(result.warnings()).contains("1.0 초과: 7");
        assertThat(result.items()).extracting(Allocation::mm_ovr_yn).containsOnly("Y");
    }
    @Test void tiesUseActualAssignmentIdRegardlessOfInputOrder() {
        var result = calculate(person("FL", 1L), List.of(assignment(20, "0.50"), assignment(10, "0.50")), List.of());
        assertThat(result.items()).extracting(Allocation::asgn_id).containsExactly(10L, 20L);
        assertThat(result.items()).extracting(Allocation::lbr_cst_amt).containsExactly(1L, 0L);
    }
    @ParameterizedTest @CsvSource({"FT,40,60", "CT,40,60", "FL,100,0", "OS,100,0"})
    void employeeAndExternalPointFourBoundary(String type, long project, long idle) {
        var result = calculate(person(type, 100L), List.of(assignment(1, "0.40")), List.of());
        assertThat(result.items().get(0).lbr_cst_amt()).isEqualTo(project);
        assertThat(result.items().stream().filter(r -> r.aloc_ty_cd().equals("IDLE")).mapToLong(Allocation::lbr_cst_amt).sum()).isEqualTo(idle);
        assertThat(result.items().get(0).mm_qty()).isEqualTo(n("0.40"));
    }
    @Test void idleParticipatesInRemainderRounding() {
        var result = calculate(person("FT", 1L), List.of(assignment(1, "0.40")), List.of());
        assertThat(result.items()).extracting(Allocation::lbr_cst_amt).containsExactly(0L, 1L);
    }
    @ParameterizedTest @ValueSource(strings = {"FT", "CT"})
    void zeroMmEmployeeRetainsEntireSalaryAndPersonalExpense(String type) {
        var expense = new Expense(1, 7L, null, null, null, 23);
        var result = calculate(person(type, 100L), List.of(), List.of(expense));
        assertThat(result.items()).singleElement().satisfies(r -> {
            assertThat(r.aloc_ty_cd()).isEqualTo("IDLE"); assertThat(r.prj_id()).isNull();
            assertThat(r.lbr_cst_amt()).isEqualTo(100); assertThat(r.drct_expns_amt()).isEqualTo(23);
        });
        assertThat(result.warnings()).contains("MM 없음: 7");
    }
    @ParameterizedTest @ValueSource(strings = {"FL", "OS"})
    void zeroMmExternalIsExplicitlyUnallocatedNeverArtificialIdle(String type) {
        var result = calculate(person(type, 100L), List.of(), List.of(new Expense(1, 7L, null, null, null, 23)));
        assertThat(result.items()).isEmpty();
        assertThat(result.unallocated()).containsExactly(new Unallocated(7, 100L, 23));
        assertThat(result.warnings()).contains("MM 없음: 7", "미배분 원가: 7");
    }
    @Test void indirectOverridesInternalAndIdleClassification() {
        var a = new Assignment(1, 7, 101, n("0.40"), "ACTIVE", "IN", "RUN", "NEW", null, null);
        var result = calculate(new Person(7, "FT", "INDIRECT", 100L), List.of(a), List.of(new Expense(1, 7L, 101L, "IN", "RUN", 11)));
        assertThat(result.items()).singleElement().satisfies(r -> {
            assertThat(r.aloc_ty_cd()).isEqualTo("INDIRECT"); assertThat(r.prj_id()).isNull();
            assertThat(r.lbr_cst_amt()).isEqualTo(100); assertThat(r.drct_expns_amt()).isEqualTo(11);
        });
    }
    @Test void internalDirectIsResearchWithNoSales() {
        var a = new Assignment(1, 7, 101, n("1.00"), "ACTIVE", "IN", "RUN", "NEW", null, null);
        var result = calculate(person("FT", 100L), List.of(a), List.of());
        assertThat(result.items().get(0).aloc_ty_cd()).isEqualTo("RND");
        assertThat(result.items().get(0).sls_amt()).isNull();
    }
    @Test void salaryAndEachExpenseAreDeduplicatedAndRoundedIndependently() {
        var expense = new Expense(1, 7L, 101L, "MO", "RUN", 1);
        var project = new Expense(2, null, 101L, "MO", "RUN", 9);
        var p = person("FL", 1L);
        var result = engine.calculate("2026-10", List.of(p, p), List.of(assignment(1, "0.50"), assignment(2, "0.50")), List.of(expense, expense, project, project));
        assertThat(result.items()).extracting(Allocation::lbr_cst_amt).containsExactly(1L, 0L);
        assertThat(result.items()).extracting(Allocation::drct_expns_amt).containsExactly(1L, 0L);
        assertThat(result.project_expenses()).containsExactly(new ProjectExpense(2, 101, "PROJECT", 9));
    }
    @Test void freeAndPaidMmBothConsumeSalaryButFreeRevenueIsZero() {
        var free = new Assignment(2, 7, 102, n("0.50"), "ACTIVE", "MO", "RUN", "FREE", null, null);
        var result = calculate(person("FT", 100L), List.of(assignment(1, "0.50"), free), List.of());
        assertThat(result.items()).extracting(Allocation::lbr_cst_amt).containsExactly(50L, 50L);
        assertThat(result.items()).extracting(Allocation::sls_amt).containsExactly(500L, 0L);
        assertThat(result.warnings()).noneMatch(w -> w.contains("단가"));
    }
    @Test void cancelledAssignmentDoesNotEnterDenominator() {
        var cancelled = new Assignment(2, 7, 102, n("1.00"), "CANCELLED", "MO", "RUN", "NEW", 1000L, null);
        assertThat(calculate(person("FL", 100L), List.of(assignment(1, "0.40"), cancelled), List.of()).items())
            .extracting(Allocation::lbr_cst_amt).containsExactly(100L);
    }
    @Test void plannedIsMarkedAndLostIsIdleNotProjectCost() {
        var plan = new Assignment(1, 7, 101, n("0.50"), "ACTIVE", "MO", "PLAN", "NEW", 1000L, null);
        var lost = new Assignment(2, 7, 102, n("0.50"), "ACTIVE", "MO", "LOST", "NEW", 1000L, null);
        var result = calculate(person("FT", 100L), List.of(plan, lost), List.of());
        assertThat(result.items()).extracting(Allocation::aloc_ty_cd).containsExactly("PROJECT", "IDLE");
        assertThat(result.items().get(0).pre_cntrct_yn()).isEqualTo("Y");
        assertThat(result.warnings()).contains("미계약 투입: 101");
    }
    @Test void missingSalaryIsNotCredibleZeroAndMissingPriceIsNotRevenueZero() {
        var a = new Assignment(1, 7, 101, n("1.00"), "ACTIVE", "MO", "RUN", "NEW", null, null);
        var result = calculate(person("FT", null), List.of(a), List.of());
        assertThat(result.items().get(0).lbr_cst_amt()).isNull();
        assertThat(result.items().get(0).sls_amt()).isNull();
        assertThat(result.warnings()).contains("급여 미업로드: 7", "단가 미등록: 1");
    }
    @Test void missingTurnkeyAllocationIsWarnedAndNeverDerivedFromCompanyBill() {
        var a = new Assignment(1, 7, 101, n("1.00"), "ACTIVE", "TK", "RUN", "NEW", null, null);
        var result = calculate(person("FT", 100L), List.of(a), List.of());
        assertThat(result.items().get(0).sls_amt()).isNull();
        assertThat(result.warnings()).contains("배분표 없음: 7/101");
    }
    @Test void turnkeyPersonMonthRevenueIsCountedOnceAcrossAssignments() {
        var a = new Assignment(1, 7, 101, n("0.50"), "ACTIVE", "TK", "RUN", "NEW", null, 101L);
        var b = new Assignment(2, 7, 101, n("0.50"), "ACTIVE", "TK", "RUN", "NEW", null, 101L);
        assertThat(calculate(person("FL", 100L), List.of(a, b), List.of()).items()).extracting(Allocation::sls_amt).containsExactly(51L, 50L);
    }
    @Test void negativeAdjustmentsAreSignSymmetricAndConserveEveryWon() {
        var assignments = List.of(assignment(1, "1.00"), assignment(2, "0.50"));
        var result = calculate(person("FT", -100L), assignments, List.of(new Expense(1, 7L, null, null, null, -100)));
        assertThat(result.items()).extracting(Allocation::lbr_cst_amt).containsExactly(-67L, -33L);
        assertThat(result.items()).extracting(Allocation::drct_expns_amt).containsExactly(-67L, -33L);
    }
    @ParameterizedTest @ValueSource(longs = {Long.MIN_VALUE, Long.MAX_VALUE, 0, -1, 1})
    void exactMoneyNeverPassesThroughDoubleOrOverflowsAbsoluteLong(long amount) {
        var result = calculate(person("FT", amount), List.of(assignment(1, "0.33"), assignment(2, "0.67")), List.of());
        BigDecimal sum = result.items().stream().map(r -> BigDecimal.valueOf(r.lbr_cst_amt())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(BigDecimal.valueOf(amount));
    }
    @Test void conflictingDuplicateSourceIsRejectedRatherThanCountedTwice() {
        assertThatThrownBy(() -> calculate(person("FT", 100L), List.of(assignment(1, "1.00")),
            List.of(new Expense(1, 7L, null, null, null, 1), new Expense(1, 7L, null, null, null, 2))))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("VALIDATION_ERROR"));
    }
    @Test void changingMonthlyMmRecalculatesWithoutChangingTheSource() {
        var p = person("FT", 100L);
        assertThat(calculate(p, List.of(assignment(1, "0.40")), List.of()).items().get(0).lbr_cst_amt()).isEqualTo(40);
        assertThat(calculate(p, List.of(assignment(1, "0.60")), List.of()).items().get(0).lbr_cst_amt()).isEqualTo(60);
        assertThat(p.indv_lbr_cst_amt()).isEqualTo(100);
    }
}
