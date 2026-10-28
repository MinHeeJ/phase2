package com.sicost.billingsops;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.billingsops.BillingModels.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BillingCalculationTest {
    @ParameterizedTest @CsvSource({"-1,0,7","0,0,7","6,0,7","7,1,30","29,1,30","30,2,60","59,2,60","60,3,-1","90,3,-1"})
    void reminderBoundariesAreInclusiveAndNextDateUsesNextThreshold(int elapsed,int stage,int next) {
        LocalDate due=LocalDate.of(2026,10,1), today=due.plusDays(elapsed);
        assertThat(BillingReceivableService.stage(due,today)).isEqualTo(stage);
        assertThat(BillingReceivableService.nextDate(due,today,1)).isEqualTo(next<0?null:due.plusDays(next));
    }
    @Test void absentDueAndFullyPaidHaveNoNextDate() {
        assertThat(BillingReceivableService.stage(null,LocalDate.now())).isNull();
        assertThat(BillingReceivableService.nextDate(null,LocalDate.now(),1)).isNull();
        assertThat(BillingReceivableService.nextDate(LocalDate.now(),LocalDate.now(),0)).isNull();
    }
    @Test void reminderRoleAccumulationAndHeadquartersAndUnassignedAreDistinct() {
        assertThat(BillingReceivableService.roles(0,new Part("파트","N"))).isEmpty();
        assertThat(BillingReceivableService.roles(1,null)).containsExactly("영업 담당자","PM");
        assertThat(BillingReceivableService.roles(2,new Part("본사","Y"))).containsExactly("영업 담당자","PM","본사 영업 책임자");
        assertThat(BillingReceivableService.roles(2,new Part("파트","N"))).contains("영업 파트장");
        assertThat(BillingReceivableService.roles(3,null)).containsExactly("영업 담당자","PM","경영진","재무");
    }
    @Test void exactToleranceIsStrictAndSupportsNegativeAmendments() {
        assertThat(BillingGapService.judgment(2000000,1990000,1,false,false)).isEqualTo("맞음");
        assertThat(BillingGapService.judgment(2000000,1989999,1,false,false)).isEqualTo("적게 청구");
        assertThat(BillingGapService.judgment(2000000,2010001,1,false,false)).isEqualTo("많이 청구");
        assertThat(BillingGapService.tolerance(2000001)).isEqualByComparingTo("10000.005");
    }
    @Test void missingAttendanceTakesPrecedenceOverFreeAndZeroMatch() {
        assertThat(BillingGapService.judgment(0,0,0,true,true)).isEqualTo("근태 미입력");
        assertThat(BillingGapService.judgment(0,0,0,true,false)).isEqualTo("맞음(무상)");
        assertThat(BillingGapService.judgment(1,0,0,false,false)).isEqualTo("청구 없음");
    }
    @Test void amountFloorsWonAndAddsManualOvertimeWithoutMultiplier() {
        assertThat(BillingAmountService.calculate(new BigDecimal("0.333"),10001,0)).isEqualTo(3330);
        assertThat(BillingAmountService.calculate(new BigDecimal("2.5"),10001,500)).isEqualTo(25502);
    }
    @Test void seoulCreationDateCrossesUtcBoundary() {
        Clock clock=Clock.fixed(Instant.parse("2026-10-31T15:01:00Z"),ZoneOffset.UTC);
        assertThat(BillingQuery.year(null,clock)).isEqualTo(2026);
        assertThat(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))).isEqualTo(LocalDate.of(2026,11,1));
    }
}
