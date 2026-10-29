package com.sicost.common.costs;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.common.costs.CostAllocationModels.*;

class CostAllocationEngineTest {
    final CostAllocationEngine engine = new CostAllocationEngine();
    static BigDecimal n(String value) { return new BigDecimal(value); }
    static Assignment assignment(long id, String mm) {
        return new Assignment(id, 7, id + 10, n(mm), "MO", "RUN", "NEW", 1000L);
    }
    Result calculate(String kind, long amount, List<Assignment> assignments, List<Expense> expenses) {
        return engine.calculate("2026-10", List.of(new Salary(7, kind, "DIRECT", amount)), assignments, expenses, List.of());
    }
    @Test void edgeOneLargestFractionGetsWonNotLastRow() {
        var r = calculate("FT", 100, List.of(assignment(2, "0.50"), assignment(1, "1.00")), List.of());
        assertThat(r.items()).extracting(Allocation::lbr_cst_amt).containsExactly(67L, 33L);
        assertThat(r.items()).extracting(Allocation::mm_qty).containsExactly(n("1.00"), n("0.50"));
        assertThat(r.warnings()).extracting(Warning::code).contains("MM_OVER");
    }
    @ParameterizedTest @CsvSource({"FT,40,60", "CT,40,60", "FL,100,0", "OS,100,0"})
    void edgeTwoEmploymentBoundary(String kind, long project, long idle) {
        var r = calculate(kind, 100, List.of(assignment(1, "0.40")), List.of());
        assertThat(r.items().get(0).lbr_cst_amt()).isEqualTo(project);
        assertThat(r.items().stream().filter(a -> "IDLE".equals(a.aloc_ty_cd()))
            .mapToLong(Allocation::lbr_cst_amt).sum()).isEqualTo(idle);
    }
    @Test void equalFractionsUseActualAssignmentIdAscending() {
        var r = calculate("FL", 1, List.of(assignment(9, "0.50"), assignment(2, "0.50")), List.of());
        assertThat(r.items()).extracting(Allocation::asgn_id).containsExactly(2L, 9L);
        assertThat(r.items()).extracting(Allocation::lbr_cst_amt).containsExactly(1L, 0L);
    }
    @Test void idleParticipatesInLargestRemainderConservation() {
        var r = calculate("FT", 1, List.of(assignment(1, "0.40")), List.of());
        assertThat(r.items()).extracting(Allocation::lbr_cst_amt).containsExactly(0L, 1L);
    }
    @Test void edgeThreeFreeSupportKeepsDenominatorAndZeroReferenceRevenue() {
        var free = new Assignment(2, 7, 12, n("0.50"), "MO", "RUN", "FREE", null);
        var r = calculate("FT", 100, List.of(assignment(1, "0.50"), free), List.of());
        assertThat(r.items()).extracting(Allocation::lbr_cst_amt).containsExactly(50L, 50L);
        assertThat(r.items().get(1).sls_amt()).isZero();
    }
    @Test void expensesDeduplicateBySourceAndProjectOnlyAppearsOnceWithoutFakeDeveloper() {
        var person = new Expense(3, 7L, 11L, 100, "MO");
        var project = new Expense(4, null, 11L, 19, "MO");
        var r = calculate("FT", 100, List.of(assignment(1, "1.00"), assignment(2, "0.50")),
            List.of(person, person, project, project));
        assertThat(r.items()).extracting(Allocation::drct_expns_amt).containsExactly(67L, 33L);
        assertThat(r.expenses()).containsExactly(new ProjectExpense(4, 11, "PROJECT", 19));
    }
    @Test void indirectNeverReallocatedToProjectOrIdle() {
        var r = engine.calculate("2026-10", List.of(new Salary(7, "FT", "INDIRECT", 100L)),
            List.of(assignment(1, "0.40")), List.of(new Expense(1, 7L, 11L, 3, "MO")), List.of());
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).aloc_ty_cd()).isEqualTo("INDIRECT");
        assertThat(r.items().get(0).prj_id()).isNull();
        assertThat(r.items().get(0).drct_expns_amt()).isEqualTo(3);
    }
    @Test void internalPlanAndLostClassifications() {
        var r = calculate("FT", 90, List.of(
            new Assignment(1, 7, 11, n("0.30"), "IN", "RUN", "NEW", null),
            new Assignment(2, 7, 12, n("0.30"), "MO", "PLAN", "NEW", 100L),
            new Assignment(3, 7, 13, n("0.40"), "MO", "LOST", "NEW", 100L)), List.of());
        assertThat(r.items()).extracting(Allocation::aloc_ty_cd).containsExactly("RND", "PROJECT", "IDLE");
        assertThat(r.items().get(0).sls_amt()).isNull();
        assertThat(r.items().get(1).pre_cntrct()).isTrue();
    }
    @Test void noMmFtIdleFlUnallocatedBothWarn() {
        var ft = calculate("FT", 100, List.of(), List.of());
        assertThat(ft.items().get(0).aloc_ty_cd()).isEqualTo("IDLE");
        var fl = calculate("FL", 100, List.of(), List.of(new Expense(1, 7L, null, 5, null)));
        assertThat(fl.items()).isEmpty();
        assertThat(fl.unallocated()).containsExactly(new Unallocated(7, 100, 5));
        assertThat(fl.warnings()).extracting(Warning::code).contains("MM_MISSING");
    }
    @Test void edgeFourMissingEvidenceWarnsAndReferenceRevenueRemainsNull() {
        var r = engine.calculate("2026-10", List.of(new Salary(7, "FT", "DIRECT", null)),
            List.of(new Assignment(1, 7, 11, n("1.00"), "TK", "RUN", "NEW", null)), List.of(), List.of());
        assertThat(r.warnings()).extracting(Warning::code).containsExactly("SALARY_MISSING", "ALLOCATION_MISSING");
        assertThat(r.items().get(0).sls_amt()).isNull();
    }
    @Test void missingPriceHasDistinctWarning() {
        var r = calculate("FT", 100, List.of(new Assignment(1, 7, 11, n("1.00"), "MO", "RUN", "NEW", null)), List.of());
        assertThat(r.warnings()).extracting(Warning::code).containsExactly("PRICE_MISSING");
    }
    @Test void turnkeyPersonRevenueIsNotRepeatedAcrossConcurrentAssignments() {
        var r = engine.calculate("2026-10", List.of(new Salary(7, "FT", "DIRECT", 100L)),
            List.of(new Assignment(1, 7, 11, n("1.00"), "TK", "RUN", "NEW", null),
                new Assignment(2, 7, 11, n("0.50"), "TK", "RUN", "NEW", null)),
            List.of(), List.of(new Turnkey(11, 7, 100)));
        assertThat(r.items()).extracting(Allocation::sls_amt).containsExactly(67L, 33L);
    }
    @Test void hugeWonAndSmallMmStayExactWithoutDouble() {
        var r = calculate("FL", 9007199254740993L, List.of(assignment(1, "0.01"), assignment(2, "0.02")), List.of());
        assertThat(r.items()).extracting(Allocation::lbr_cst_amt).containsExactly(3002399751580331L, 6004799503160662L);
    }
    @Test void eachExpenseComponentRoundsSeparatelyIncludingNegativeAmounts() {
        var r = calculate("FT", 0, List.of(assignment(1, "0.50"), assignment(2, "0.50")),
            List.of(new Expense(1, 7L, null, -1, null), new Expense(2, 7L, null, -1, null)));
        assertThat(r.items()).extracting(Allocation::drct_expns_amt).containsExactly(0L, -2L);
    }
}
