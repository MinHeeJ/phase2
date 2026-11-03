package com.sicost.opportunitiesops;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.opportunities.OpportunityModels;
import com.sicost.opportunities.OpportunityQuoteCalculator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.sicost.opportunitiesops.OpportunityLifecycleModels.*;

class OpportunityQuoteConsistencyTest {
    private final LocalDate today=LocalDate.of(2026,10,23);
    private CostSource source(long id,String job,String grade,String amount) {
        return new CostSource(id,id,"2026-10",new BigDecimal(amount),BigDecimal.ZERO,job,grade);
    }
    private void sameQuote(String grade,List<CostSource> sources) {
        var input=new OpportunityModels.NeedInput(11L,"BE",grade,2,"2026-11",3,new BigDecimal("0.50"),1000000L,null);
        var save=new OpportunityModels.Save("기회",1L,1L,null,"DISC",BigDecimal.TEN,3000000,"MO","2026-11",3,null,null,null,List.of(input),null);
        var costs=sources.stream().map(s->new OpportunityModels.CostSource(s.developer(),s.job(),s.grade(),s.id(),s.month(),s.labor().add(s.expense()))).toList();
        var stored=OpportunityQuoteCalculator.calculate(save,costs,Map.of(),new BigDecimal("20"));
        var mapper=mock(OpportunityLifecycleMapper.class);
        when(mapper.costSources(today)).thenReturn(sources);
        var need=new Need(11,"BE",grade,2,"2026-11",3,new BigDecimal("0.50"),1000000L,null,null,null,null);
        var verified=new OpportunityQuoteVerifier(mapper).calculate(null,List.of(need),today);
        assertThat(verified.revenue()).isEqualTo(stored.revenue());
        assertThat(verified.cost()).isEqualTo(stored.cost());
        assertThat(verified.margin()).isEqualByComparingTo(stored.margin());
        assertThat(verified.known()).isEqualTo(!stored.unknown());
        assertThat(verified.fingerprint()).isEqualTo(stored.sourceSignature());
    }
    @Test void smallSampleUsesGradeAverageAndFloorsUnitBeforeMultiplication() {
        sameQuote("SR",List.of(source(1,"BE","SR","600001"),source(2,"FE","SR","600002")));
    }
    @Test void missingGradeFallsBackToSameJobNotUnrelatedPeople() {
        sameQuote("SR",List.of(source(1,"BE","MID","600001"),source(2,"FE","MID","900000")));
    }
    @Test void unrelatedSourcesRemainUnknown() {
        sameQuote("SR",List.of(source(1,"FE","MID","900000")));
    }
    @Test void nullGradeUsesSameJobFallback() {
        sameQuote(null,List.of(source(1,"BE",null,"600001"),source(2,"FE","SR","900000")));
    }
    @Test void zeroCostProofUsesTheSignatureReadAndRetainedByOpportunityService() {
        sameQuote("SR",List.of(source(2,"BE","SR","0"),source(1,"BE","SR","0")));
        var mapper=mock(OpportunityLifecycleMapper.class);
        when(mapper.costSources(today)).thenReturn(List.of(source(1,"BE","SR","0")));
        var verifier=new OpportunityQuoteVerifier(mapper);
        var need=new Need(11,"BE","SR",1,"2026-11",1,BigDecimal.ONE,1000000L,0L,0L,0L,null);
        var snapshot=verifier.calculate(null,List.of(need),today);
        assertThat(verifier.zeroProof(snapshot,"cst.mth_cst:1")).isTrue();
    }
}
