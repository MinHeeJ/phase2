package com.sicost.projects;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.sicost.apiops.*;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.projects.ProjectProfitModels.*;
import static com.sicost.projects.ProjectProfitCalculator.*;

class ProjectProfitCalculatorTest {
    BigDecimal n(String s) { return new BigDecimal(s); }
    PersonMonth person(long id,String mm,long cost,Long sales,boolean free) {
        return new PersonMonth("2026-10",id,"시험",id,n(mm),cost,0,sales,free,false);
    }
    @ParameterizedTest @CsvSource({"14.9,20,true","15,20,false","15.1,20,false","24.9,30,true","25,30,false"})
    void lowMarginIsStrictlyMoreThanFivePoints(String actual,String target,boolean low) {
        assertThat(lowMargin(n(actual),n(target))).isEqualTo(low);
    }
    @ParameterizedTest @CsvSource({"319,400,NORMAL","320,400,WARN","399,400,WARN","400,400,RISK","500,400,RISK","1,0,UNAVAILABLE"})
    void budgetThresholdsAreInclusive(long cost,long budget,String expected) {
        assertThat(budgetAlert(cost,budget)).isEqualTo(expected);
    }
    @Test void nullBudgetAndZeroDenominatorsAreUnavailable() {
        assertThat(budgetAlert(10,null)).isEqualTo("UNAVAILABLE");
        assertThat(margin(0L,10)).isNull();
        assertThat(ratio(n("10"),BigDecimal.ZERO)).isNull();
    }
    @Test void lowMarginJudgmentDoesNotUseRoundedDisplayRate() {
        assertThat(margin(100000L,85001)).isEqualByComparingTo("15.0");
        assertThat(lowMarginFor(100000L,85001,n("20"))).isTrue();
        assertThat(lowMarginFor(100000L,85000,n("20"))).isFalse();
    }
    @Test void threeMmAgainstTwoAtHundredPerMmHasHundredStaffingCause() {
        var result=causes(ProjectProfitApiTest.source("TK",400L,"20"),2026,
            List.of(person(1,"3",300,1000L,false)),n("2"),n("80"),List.of());
        assertThat(result.items().get(0).amount()).isEqualByComparingTo("100");
        assertThat(result.primary_cause()).isEqualTo("STAFFING");
    }
    @Test void tiesPreferStaffingThenPricingThenFreeAndZeroHasNoPrimary() {
        var result=causes(ProjectProfitApiTest.source("TK",400L,"20"),2026,
            List.of(person(1,"3",300,250L,false)),n("2"),n("80"),List.of());
        assertThat(result.items().stream().map(Cause::code)).containsExactly("STAFFING","PRICING","FREE");
        assertThat(result.primary_cause()).isEqualTo("STAFFING");
        var empty=causes(ProjectProfitApiTest.source("TK",400L,"20"),2026,List.of(),n("0"),n("80"),List.of());
        assertThat(empty.primary_cause()).isNull();
    }
    @Test void pricingIsAggregatedPerPersonMonthBeforePositivePartAndFreeCostIsExplanationOnly() {
        var rows=List.of(person(1,"0.5",40,0L,true),person(1,"0.5",60,200L,false));
        var result=causes(ProjectProfitApiTest.source("TK",400L,"20"),2026,rows,n("1"),n("80"),List.of());
        assertThat(result.items().stream().filter(c->c.code().equals("PRICING")).findFirst().orElseThrow().amount()).isZero();
        assertThat(result.items().get(0).amount()).isEqualByComparingTo("40");
        assertThat(rows.stream().mapToLong(PersonMonth::lbr_cst_amt).sum()).isEqualTo(100);
    }
    @Test void simulationUsesMoneyNotHeadcountTimesMmAndMarksVirtual() {
        var result=simulate("MO",new Scenario(2,n("100"),3,n("150")));
        assertThat(result.simulated()).isTrue();
        assertThat(result.cost_change()).isEqualByComparingTo("600");
        assertThat(result.revenue_change()).isEqualByComparingTo("900");
        assertThat(result.profit_change()).isEqualByComparingTo("300");
        assertThat(simulate("TK",new Scenario(-1,n("100.1"),1,null)).cost_change()).isEqualByComparingTo("-101");
    }
    @Test void simulationValidationRejectsMissingPriceAndNonpositiveMonths() {
        assertThatThrownBy(()->simulate("MO",new Scenario(1,n("100"),1,null))).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThatThrownBy(()->simulate("TK",new Scenario(1,n("100"),0,null))).isInstanceOf(com.sicost.common.api.ApiException.class);
    }
    @ParameterizedTest @CsvSource({"400,80.0","350,91.4"})
    void legacyProgressUsesStoredBudgetForTwentyAndOtherTargets(long budget,String use) {
        var s=new ProjectBoardModels.BoardSource(7,"P-2607","시험",null,"TK",null,null,500L,null,null,n("50"),0,0,0,0,budget);
        var keys=List.of("SIG_DELAY_WARN","SIG_DELAY_RISK","SIG_COST_WARN","SIG_COST_RISK","SIG_STALE_DAYS","BUDGET_RATE");
        var params=keys.stream().map(k->new ProjectBoardModels.OperatingParam(k,"2000-01",k,n("20"),null,null,null)).toList();
        var result=ProjectProgressCalculator.calculate(s,List.of(),new ProjectBoardModels.ProjectCost(320,List.of()),params,LocalDate.of(2026,10,23));
        assertThat(result.budget_amt()).isEqualTo(budget);
        assertThat(result.cost_use_rt()).isEqualByComparingTo(use);
    }
}
