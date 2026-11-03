package com.sicost.opportunities;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.opportunities.OpportunityModels.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OpportunityQuoteCalculatorTest {
    NeedInput need(Long id,String job,String grade,int people,String mm,int months,long price){return new NeedInput(id,job,grade,people,"2026-11",months,new BigDecimal(mm),price,null);}
    Save input(List<NeedInput> needs){return new Save("기회",1L,1L,null,"DISC",null,0,"MO","2026-11",3,null,null,null,needs,"별도 협의");}
    CostSource source(long id,String job,String grade,String cost){return new CostSource(id,job,grade,id,"2026-10",new BigDecimal(cost));}
    Calculation calculate(List<NeedInput> needs,List<CostSource> sources){return OpportunityQuoteCalculator.calculate(input(needs),sources,Map.of(),new BigDecimal("20"));}
    @Test void decimalProductsAreSummedBeforeFinalFloor(){var n=need(null,"BE","MID",1,"0.33",1,1);var c=calculate(List.of(n,n,n,n),List.of(source(1,"BE","MID","1"),source(2,"BE","MID","1")));assertThat(c.revenue()).isEqualTo(1);assertThat(c.cost()).isEqualTo(1);assertThat(c.margin()).isEqualByComparingTo("0.00");}
    @Test void sampleAndTargetPrice(){var c=calculate(List.of(need(null,"BE","MID",2,"0.50",3,1000000)),List.of(source(1,"BE","MID","600000"),source(2,"BE","MID","600000")));assertThat(c.revenue()).isEqualTo(3000000);assertThat(c.cost()).isEqualTo(1800000);assertThat(c.margin()).isEqualByComparingTo("40.00");assertThat(c.needs().get(0).target()).isEqualTo(750000);}
    @Test void sameGradeFallbackIncludesOtherJobsAndFloorsAverage(){var c=calculate(List.of(need(null,"BE","MID",1,"1",1,10)),List.of(source(1,"BE","MID","1"),source(2,"FE","MID","2")));assertThat(c.needs().get(0).cost()).isEqualTo(1);assertThat(c.unknown()).isFalse();}
    @Test void absentGradeUsesJobFallback(){var c=calculate(List.of(need(null,"BE","SENIOR",1,"1",1,10)),List.of(source(1,"BE","MID","3")));assertThat(c.needs().get(0).cost()).isEqualTo(3);assertThat(c.warnings()).contains("등급 원가 없음: 직무 전체 평균 적용");}
    @Test void noSourcesIsUnknownNotProvenZero(){var c=calculate(List.of(need(null,"BE","MID",1,"1",1,10)),List.of());assertThat(c.unknown()).isTrue();assertThat(c.margin()).isEqualByComparingTo("100.00");}
    @Test void realZeroSourceIsNotMissingSource(){var c=calculate(List.of(need(null,"BE","MID",1,"1",1,10)),List.of(source(1,"BE","MID","0"),source(2,"BE","MID","0")));assertThat(c.unknown()).isFalse();assertThat(c.sourceSignature()).contains("mth_cst_id=1");}
    @Test void absentPriceUsesDbDefaultButZeroCannotSave(){var n=new NeedInput(null,"BE","MID",1,"2026-11",1,BigDecimal.ONE,null,null);var c=OpportunityQuoteCalculator.calculate(input(List.of(n)),List.of(),Map.of("BE",12L),new BigDecimal("20"));assertThat(c.revenue()).isEqualTo(12);assertThatThrownBy(()->OpportunityQuoteCalculator.calculate(input(List.of(n)),List.of(),Map.of(),new BigDecimal("20"))).isInstanceOf(com.sicost.common.api.ApiException.class);}
    @Test void revenueZeroMarginAndZeroDenominatorAreNull(){var c=calculate(List.of(),List.of());assertThat(c.margin()).isNull();assertThat(OpportunityQuoteCalculator.rate(BigDecimal.TEN,0)).isNull();assertThat(OpportunityQuoteCalculator.rate(BigDecimal.ONE,6)).isEqualByComparingTo("16.67");}
    Row approved(BigDecimal margin){return new Row(1,"O-01","기회",1L,1L,null,"PROP",new BigDecimal("30"),3000000,"MO","2026-11",3,null,null,null,null,"OPEN",null,null,LocalDateTime.of(2026,10,1,0,0),"APPROVED",3000000L,1800000L,new BigDecimal("40"),margin,null,null);}
    @Test void exactQuoteAndNeedsAndHalfPointRetainApproval(){var n=need(1L,"BE","MID",2,"0.50",3,1000000);var c=calculate(List.of(n),List.of(source(1,"BE","MID","600000"),source(2,"BE","MID","600000")));var old=List.of(new NeedRow(1,"BE","MID",2,"2026-11",3,new BigDecimal("0.50"),1000000,null,600000,600000,750000));assertThat(OpportunityQuoteCalculator.retains(approved(new BigDecimal("40.50")),old,c,new BigDecimal("0.50"))).isTrue();assertThat(OpportunityQuoteCalculator.retains(approved(new BigDecimal("40.51")),old,c,new BigDecimal("0.50"))).isFalse();var changed=calculate(List.of(need(1L,"BE","MID",3,"0.50",2,1000000)),List.of(source(1,"BE","MID","600000"),source(2,"BE","MID","600000")));assertThat(OpportunityQuoteCalculator.retains(approved(new BigDecimal("40")),old,changed,new BigDecimal("0.50"))).isFalse();}
    @Test void boundsWarnWithoutClipping(){assertThat(OpportunityQuoteCalculator.outside("2026-11",3,"2026-10",1)).isTrue();assertThat(OpportunityQuoteCalculator.outside("2026-11",3,"2027-01",1)).isFalse();}
}
