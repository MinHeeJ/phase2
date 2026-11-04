package com.sicost.apiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.cashflow.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.opportunities.*;
import com.sicost.parts.*;
import com.sicost.us1.WithdrawalMapper;
import com.sicost.withdrawalsops.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardAdditionalOwnerReadTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    OpportunityModels.Row opportunity(long id,String month,long amount,String probability) {
        return new OpportunityModels.Row(id,"O-"+id,"기회 "+id,1L,2L,null,"PROP",new BigDecimal(probability),amount,
            "MO",month,3,null,null,null,null,"OPEN",null,null,LocalDateTime.of(2026,10,20,0,0),
            "NONE",80000000L,null,null,null,null,null);
    }
    @Test void pipelineFloorsEachOpportunityAndExcludesStartedOnlyFromUpcomingList() {
        var mapper=mock(OpportunityMapper.class);
        var owner=new OpportunityService(mapper,new CurrentUser(),clock,new ObjectMapper());
        var rows=List.of(opportunity(10,"2026-10",100000000,"50"),opportunity(11,"2026-11",3,"50"),
            opportunity(12,"2026-11",3,"50"),opportunity(13,"2026-12",10,"10"),opportunity(14,"2027-01",10,"10"));
        when(mapper.matching(any())).thenReturn(rows);
        var result=owner.readPipeline(LocalDate.of(2026,10,29));
        assertThat(result.openCount()).isEqualTo(5);
        assertThat(result.weightedAmount()).isEqualTo(50000004);
        assertThat(result.startingTotal()).isEqualTo(4);
        assertThat(result.starting()).extracting(OpportunityModels.Row::oppt_id).containsExactly(11L,12L,13L);
        verify(mapper,times(1)).matching(argThat(q->q.status().equals("OPEN")));
        verifyNoMoreInteractions(mapper);
    }
    @Test void zeroWithdrawalRecommendationsNeedNoInventedAssignmentAndNeverLoadTemporaryWrites() {
        var mapper=mock(NextSlotMapper.class);var withdrawals=mock(WithdrawalMapper.class);
        var owner=new NextSlotService(mapper,withdrawals,clock);
        var result=owner.readSummary(LocalDate.of(2026,10,29));
        assertThat(result.withoutNext()).isZero();assertThat(result.withRecommendation()).isZero();
        verify(mapper,never()).temporary(anyLong());
    }
    @Test void closedPartReadUsesConfirmedSettlementOnlyAndDoesNotTouchMutableRevenue() {
        var mapper=mock(PartSettlementMapper.class);var allocation=mock(com.sicost.costallocation.CostAllocationService.class);
        var billing=mock(BillingRevenueQuery.class);
        var owner=new PartSettlementService(mapper,allocation,mock(PartSettlementApprovalPolicy.class),clock,new ObjectMapper(),new CurrentUser());
        org.springframework.test.util.ReflectionTestUtils.setField(owner,"revenue",billing);
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.dashboardParts()).thenReturn(List.of(new PartSettlementModels.DashboardPart(17,"본사","HQ"),new PartSettlementModels.DashboardPart(18,"영업","PART")));
        var before=owner.readSummary("2026-09");
        assertThat(before).allSatisfy(r->{assertThat(r.revenue()).isNull();assertThat(r.margin()).isNull();assertThat(r.marginState()).isEqualTo("MISSING_COST");});
        verifyNoInteractions(billing,allocation);
        verify(mapper,never()).people(any());
        verify(mapper,never()).saveCard(any());
    }
    @Test void cashForecastContinuityMatchesUi21WhilePlanAndActualRemainSeparate() {
        var mapper=mock(CashFlowMapper.class);var billing=mock(BillingRevenueQuery.class);
        var excel=spy(new ExcelExportService());
        var owner=new CashFlowService(mapper,billing,clock,excel,new CurrentUser(),new PermissionService());
        when(mapper.initial()).thenReturn(new CashFlowModels.Balance("2026-10",1000000L,null,null,"Y","OPEN"));
        when(mapper.minimums()).thenReturn(List.of(new CashFlowModels.Minimum("2026-01",1000000L)));
        when(mapper.events()).thenReturn(List.of(new CashFlowModels.Event(LocalDate.of(2026,11,1),100000,null,0,100000,true),
            new CashFlowModels.Event(LocalDate.of(2026,12,1),200000,null,0,200000,true)));
        var projection=owner.readForecast(LocalDate.of(2026,10,29));
        assertThat(projection).extracting(CashFlowModels.ForecastMonth::closing).containsExactly(1100000L,1300000L,1300000L);
        assertThat(projection).extracting(CashFlowModels.ForecastMonth::minimum).containsOnly(1000000L);
        verifyNoInteractions(excel);
        var ui=owner.flow(2026,"BOTH");
        assertThat(ui.months().get(10).forecast_closing()).isEqualTo(projection.get(0).closing());
        assertThat(ui.months().get(11).forecast_closing()).isEqualTo(projection.get(1).closing());
        assertThat(ui.months().get(11).planned_closing()).isEqualTo(1300000L);
        assertThat(ui.months().get(11).actual_closing()).isNull();
        verify(mapper,never()).saveOpening(any());verify(mapper,never()).audit(any());
    }
    @Test void cashMissingMinimumRemainsNullRatherThanZero() {
        var mapper=mock(CashFlowMapper.class);
        when(mapper.initial()).thenReturn(new CashFlowModels.Balance("2026-10",1000000L,null,null,"Y","OPEN"));
        var owner=new CashFlowService(mapper,mock(BillingRevenueQuery.class),clock,new ExcelExportService(),new CurrentUser(),new PermissionService());
        assertThat(owner.readForecast(LocalDate.of(2026,10,29))).allSatisfy(r->assertThat(r.minimum()).isNull());
    }
}
