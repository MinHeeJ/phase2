package com.sicost.parts;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.sicost.parts.PartSettlementModels.*;
import static org.assertj.core.api.Assertions.*;

class PartSettlementCalculationTest {
    private final Key key = new Key(7,"2026-10");
    private Standard standard(long fee) {
        return new Standard(1,"2026-01",null,"파트장",new BigDecimal("40"),fee);
    }
    private Salary salary(long id,String type,long pay,long retirement,String first) {
        return new Salary(id,"인력",type,"DIRECT",pay,retirement,LocalDate.parse(first),"프로젝트");
    }
    @Test void negativePeopleOffsetAndCardIsDeductedOnce() {
        var people=PartSettlementCalculator.people(key,standard(0),List.of(
            salary(11,"FT",100,0,"2025-01-01"),salary(12,"FL",100,0,"2025-01-01")),
            List.of(PartSettlementApiTest.row(11,200),PartSettlementApiTest.row(12,80)),new LinkedHashSet<>());
        var header=PartSettlementCalculator.header(key,PartSettlementApiTest.header("DRAFT",10),standard(0),people);
        assertThat(people).extracting(Person::prft_amt).containsExactly(40L,-8L);
        assertThat(header.part_prft_amt()).isEqualTo(22L);
    }
    @Test void negativeProfitTruncatesTowardZeroNeverClampedOrFloored() {
        var people=PartSettlementCalculator.people(key,standard(0),List.of(salary(11,"FL",103,0,"2025-01-01")),
            List.of(PartSettlementApiTest.row(11,100)),new LinkedHashSet<>());
        assertThat(people.get(0).prft_amt()).isEqualTo(-1L);
    }
    @Test void firstMonthUsesInclusiveCalendarDaysAndSalaryOnlyOnceAcrossProjects() {
        var rows=List.of(PartSettlementApiTest.row(11,200),
            new com.sicost.common.costallocation.CostAllocationModels.Allocation("2026-10",11,2L,12L,
                "PROJECT",new BigDecimal("0.50"),new BigDecimal("1.50"),BigDecimal.ONE,50L,0,100L,200L,"Y","N","N"));
        var people=PartSettlementCalculator.people(key,standard(10),List.of(salary(11,"FT",3100,120,"2026-10-17")),rows,new LinkedHashSet<>());
        assertThat(people).hasSize(1);
        assertThat(people.get(0).slry_amt()).isEqualTo(1500L);
        assertThat(people.get(0).rtrpay_amt()).isEqualTo(120L);
        assertThat(people.get(0).tot_exp_amt()).isEqualTo(1630L);
        assertThat(people.get(0).mm_qty()).isEqualByComparingTo("1.50");
    }
    @Test void leapMonthAndLastDayAreIncluded() {
        assertThat(PartSettlementCalculator.prorated(2900,LocalDate.parse("2024-02-29"),YearMonth.of(2024,2))).isEqualTo(100L);
        assertThat(PartSettlementCalculator.prorated(3100,LocalDate.parse("2026-09-30"),YearMonth.of(2026,10))).isEqualTo(3100L);
    }
    @Test void externalStaffHasNoSalesFeeAndFreelancerDoesButNoRetirement() {
        var people=PartSettlementCalculator.people(key,standard(50),List.of(
            salary(11,"OS",100,999,"2025-01-01"),salary(12,"FL",100,999,"2025-01-01")),
            List.of(PartSettlementApiTest.row(11,200),PartSettlementApiTest.row(12,80)),new LinkedHashSet<>());
        assertThat(people).extracting(Person::bsn_fee_amt).containsExactly(0L,50L);
        assertThat(people).extracting(Person::rtrpay_amt).containsExactly(0L,0L);
    }
    @Test void missingStandardIsUnavailableNotGlobalZeroFallback() {
        var warnings=new LinkedHashSet<String>();
        var people=PartSettlementCalculator.people(key,null,List.of(salary(11,"FT",100,0,"2025-01-01")),
            List.of(PartSettlementApiTest.row(11,200)),warnings);
        assertThat(people.get(0).bsn_fee_amt()).isNull();
        assertThat(people.get(0).prft_amt()).isNull();
        assertThat(PartSettlementCalculator.header(key,null,null,people).part_prft_amt()).isNull();
    }
    @Test void defaultPolicyNeverConfusesTestsWithApproval() {
        assertThat(new PartSettlementApprovalPolicy().approved(key,standard(0))).isFalse();
    }
}
