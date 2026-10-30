package com.sicost.parts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.parts.PartSettlementModels.*;
import static com.sicost.parts.PartSettlementCalculator.*;

class PartSettlementCalculatorTest {
    final Standard standard = new Standard(7,"2026-01",null,"파트장",new BigDecimal("40"),150);
    Person payroll(String type, long salary, long retirement, String start) {
        return new Person(8,"개발자",type,salary,retirement,LocalDate.parse(start));
    }
    @Test void calendarDayProrationOnlyInFirstAssignmentMonth() {
        assertThat(salary(payroll("FT",3100,0,"2026-10-16"),java.time.YearMonth.of(2026,10))).isEqualTo(1600);
        assertThat(salary(payroll("FT",3100,0,"2026-09-16"),java.time.YearMonth.of(2026,10))).isEqualTo(3100);
        assertThat(salary(payroll("FT",2900,0,"2024-02-29"),java.time.YearMonth.of(2024,2))).isEqualTo(100);
    }
    @Test void salaryUsesActualPaymentFeeIncludesFreelancersAndRetirementOnlyFt() {
        Item ft = person(payroll("FT",1000,50,"2025-01-01"),"2026-10",standard,List.of(),null);
        Item fl = person(payroll("FL",1000,50,"2025-01-01"),"2026-10",standard,List.of(),null);
        Item os = person(payroll("OS",1000,50,"2025-01-01"),"2026-10",standard,List.of(),null);
        assertThat(ft.tot_exp_amt()).isEqualTo(1200);
        assertThat(fl.tot_exp_amt()).isEqualTo(1150);
        assertThat(os.tot_exp_amt()).isEqualTo(1000);
    }
    @Test void signedProfitTruncatesTowardZeroRatherThanFloor() {
        var tiny = new Standard(1,"2026-01",null,null,new BigDecimal("40"),0);
        var row = person(payroll("OS",3,0,"2025-01-01"),"2026-10",tiny,List.of(),null);
        assertThat(row.prft_amt()).isEqualTo(-1);
    }
    @Test void negativeOffsetsRemainAndCardIsDeductedOnce() {
        Item positive = new Item(null,1,"양수","FL",null,BigDecimal.ONE,1000,0,0,0,0,1000,400,null);
        Item negative = new Item(null,2,"음수","FL",null,BigDecimal.ONE,0,100,0,0,100,-100,-40,null);
        Settlement stored = new Settlement(1L,7,"2026-10","DRAFT",null,null,null,null,0,0,0,0,0,20,0,"비고",null);
        var total = summarize(new Scope(7,"2026-10"),stored,standard,List.of(positive,negative));
        assertThat(total.tot_psn_prft_amt()).isEqualTo(360);
        assertThat(total.part_prft_amt()).isEqualTo(340);
        assertThat(total.rmrk_desc()).isEqualTo("비고");
    }
    @Test void standardSelectsEffectiveLatestStartAndHonorsEnd() {
        var ended = new Standard(8,"2026-09","2026-09",null,BigDecimal.TEN,1);
        assertThat(effective(List.of(ended,standard),"2026-10")).isEqualTo(standard);
        assertThat(effective(List.of(ended,standard),"2026-09")).isEqualTo(ended);
        assertThat(effective(List.of(ended),"2026-10")).isNull();
    }
    @Test void pendingPolicyIsSafeWithoutConfiguration() {
        assertThat(new PartSettlementApprovalPolicy().approved()).isFalse();
    }
    @Test void syntheticIdleDoesNotIncreaseSourceAssignmentMm() {
        var assigned = new com.sicost.costallocation.CostAllocationModels.AllocationRow("2026-10",8L,10L,2L,"PROJECT",
            new BigDecimal("0.50"),new BigDecimal("0.50"),new BigDecimal("0.5"),1,0,500,1000L,"N","N","N");
        var idle = new com.sicost.costallocation.CostAllocationModels.AllocationRow("2026-10",8L,null,null,"IDLE",
            new BigDecimal("0.50"),new BigDecimal("0.50"),new BigDecimal("0.5"),1,0,0,null,"N","N","N");
        var item = person(payroll("FT",1000,0,"2025-01-01"),"2026-10",standard,List.of(assigned,idle),null);
        assertThat(item.mm_qty()).isEqualByComparingTo("0.50");
        assertThat(item.mth_untprc_amt()).isEqualTo(500);
    }
    @Test void sourceMmAndRevenueAreSummedPerActualPersonAcrossAssignments() {
        var a = new com.sicost.costallocation.CostAllocationModels.AllocationRow("2026-10",8L,10L,2L,"PROJECT",
            new BigDecimal("0.50"),BigDecimal.ONE,new BigDecimal("0.5"),1,0,500,1000L,"N","N","N");
        var b = new com.sicost.costallocation.CostAllocationModels.AllocationRow("2026-10",8L,11L,3L,"PROJECT",
            new BigDecimal("0.50"),BigDecimal.ONE,new BigDecimal("0.5"),1,0,0,1000L,"N","Y","N");
        var item = person(payroll("FL",3100,0,"2026-10-16"),"2026-10",standard,List.of(a,b),"가 · 나");
        assertThat(item.mm_qty()).isEqualByComparingTo("1.00");
        assertThat(item.slry_amt()).isEqualTo(1600);
        assertThat(item.bsn_fee_amt()).isEqualTo(150);
        assertThat(item.mth_untprc_amt()).isEqualTo(500);
    }
}
