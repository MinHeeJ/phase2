package com.sicost.projects;

import com.sicost.apiops.ProjectBoardModels.*;
import com.sicost.apiops.ProjectProgressCalculator;
import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.common.security.PermissionService;
import com.sicost.costallocation.CostAllocationService;
import com.sicost.costallocation.CostAllocationModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static com.sicost.projects.CostProjectModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CostProjectRulesTest {
    static BigDecimal n(String v) { return new BigDecimal(v); }
    Source source(Long budget,BigDecimal progress) {
        return new Source(7,"P-2607","프로젝트","TK",500L,500L,budget,n("20"),LocalDate.of(2026,12,31),null,progress);
    }
    @Test void tiesUseContractOrderAndZerosHaveNoPrimary() {
        var result=CostProjectCauses.calculate(new CostProjectCauses.Basis(source(400L,n("50")),n("0.50"),n("1"),100,50,50));
        assertThat(result.items()).extracting(Cause::cause_code).containsExactly("OVER_MM","COST_RATE","FREE_SUPPORT");
        assertThat(result.primary_cause()).isEqualTo("OVER_MM");
        assertThat(result.unexplained_amt()).isEqualTo(-250);
        var zero=CostProjectCauses.calculate(new CostProjectCauses.Basis(source(null,null),n("1"),n("1"),0,0,0));
        assertThat(zero.primary_cause()).isNull();
        assertThat(zero.progress_excess_amt()).isNull();
    }
    @ParameterizedTest @CsvSource({"400000000,57.5","300000000,76.7"})
    void boardUsesStoredBudgetForTwentyAndDifferentTargetMargin(long budget,String expected) {
        var p=new BoardSource(7,"P-2607","프로젝트",null,"TK",null,null,500000000L,
            null,null,n("42"),0,0,0,0,budget);
        var params=new ArrayList<OperatingParam>();
        var values=Map.of("SIG_DELAY_WARN","5","SIG_DELAY_RISK","10","SIG_COST_WARN","5","SIG_COST_RISK","15","SIG_STALE_DAYS","14","BUDGET_RATE","80");
        values.forEach((k,v)->params.add(new OperatingParam(k,"2026-01",k,n(v),null,null,null)));
        var row=ProjectProgressCalculator.calculate(p,List.of(),new ProjectCost(230000000,List.of()),params,LocalDate.of(2026,10,29));
        assertThat(row.budget_amt()).isEqualTo(budget);
        assertThat(row.cost_use_rt()).isEqualByComparingTo(expected);
    }
    @Test void futurePlanMmIsMultipliedByLatestLaborCostAndFallbackUsesRemainingMonths() {
        var mapper=mock(CostProjectMapper.class);
        var allocations=mock(CostAllocationService.class);
        var revenue=mock(BillingRevenueQuery.class);
        var clock=Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));
        var service=new CostProjectService(mapper,allocations,revenue,new PermissionService(),clock);
        when(mapper.source(7)).thenReturn(source(400L,n("50")));
        when(mapper.parameter(anyString(),anyString())).thenReturn(n("20"));
        when(allocations.year(2026,7L)).thenReturn(List.of(new Allocation("2026-10","2026-10",false,"원",List.of(),
            List.of(new AllocationRow("2026-10",9L,1L,7L,"PROJECT",n("1"),n("1"),n("1"),100,0,0,null,"N","N","N")),0)));
        when(mapper.labor(any())).thenReturn(List.of(new Labor(9,"사람",30L,n("1.50"))));
        assertThat(service.detail(7,2026).project().estimated_cost_amt()).isEqualTo(145);
        when(mapper.labor(any())).thenReturn(List.of(new Labor(9,"사람",30L,null)));
        assertThat(service.detail(7,2026).project().estimated_cost_amt()).isEqualTo(160);
        when(mapper.labor(any())).thenReturn(List.of(new Labor(9,"사람",null,n("1.50"))));
        assertThat(service.detail(7,2026).project().estimated_cost_amt()).isNull();
    }
}
