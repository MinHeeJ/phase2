package com.sicost.billingsops;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.security.PermissionService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static com.sicost.billingsops.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BillingQueryTest {
    BillingMapper mapper;
    Clock clock=Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    PermissionService permissions=new PermissionService();
    BillingAmountService amounts;
    @BeforeEach void setup() {mapper=mock(BillingMapper.class);amounts=new BillingAmountService(mapper,clock,permissions);}
    Source source(String kind,Long price,Long attendance,BigDecimal mm,BigDecimal days,BigDecimal hours) {
        return new Source(1,2,3,"계약인력","K-0002",kind,LocalDate.of(2026,10,1),mm,price,attendance,days,hours,BigDecimal.ZERO,new BigDecimal("20"));
    }
    @ParameterizedTest @CsvSource({"6,0,7","7,1,30","29,1,30","30,2,60","59,2,60","60,3,-1","90,3,-1","-1,0,7","0,0,7"})
    void reminderExactBoundariesAndNextSchedule(int days,int stage,int next) {
        LocalDate due=LocalDate.of(2026,10,1);
        assertThat(BillingReceivableService.stage(days)).isEqualTo(stage);
        assertThat(BillingReceivableService.next(due,days,1)).isEqualTo(next<0?null:due.plusDays(next));
    }
    @Test void noDateOrPaidHasNoScheduleOrRecipients() {
        assertThat(BillingReceivableService.stage(null)).isNull();
        assertThat(BillingReceivableService.next(null,null,1)).isNull();
        assertThat(BillingReceivableService.next(LocalDate.now(clock),1,0)).isNull();
        assertThat(BillingReceivableService.roles(null,null,null)).isEmpty();
        assertThat(BillingReceivableService.roles(0,"영업","N")).isEmpty();
    }
    @Test void reminderRolesAreCumulativeAndNeverAccounts() {
        assertThat(BillingReceivableService.roles(3,"본사","Y")).containsExactly("영업 담당자","PM","본사 영업 책임자","경영진","재무");
        assertThat(BillingReceivableService.roles(2,null,"N")).containsExactly("영업 담당자","PM","경영진");
        assertThat(BillingReceivableService.roles(2,"파트","N")).contains("영업 파트장");
    }
    @ParameterizedTest @CsvSource({"0,0,0,true,false,근태 미입력","0,0,0,false,true,맞음(무상)","100000,0,0,false,false,청구 없음","100000,90000,1,false,false,맞음","100000,89999,1,false,false,적게 청구","100000,110001,1,false,false,많이 청구","10000000,9950000,1,false,false,맞음","10000000,9949999,1,false,false,적게 청구"})
    void gapPrecedenceAndExactTolerance(long basis,long supply,int count,boolean missing,boolean free,String expected) {
        assertThat(BillingGapService.judgment(basis,supply,count,missing,free)).isEqualTo(expected);
    }
    @Test void zeroPaidBasisWithNoConfirmedIsExcluded() {assertThat(BillingGapService.judgment(0,0,0,false,false)).isNull();}
    @ParameterizedTest @CsvSource({"MONTH,0.53,0,0,530000","DAY,0.53,2.5,0,2500000","HOUR,0.53,0,3.5,3500000"})
    void effectivePriceAndQuantityProduceFlooredWon(String basis,String mm,String days,String hours,long expected) {
        when(mapper.project(1)).thenReturn(new Project(1,"프로젝트","DP",basis,"Y"));
        when(mapper.sources(any())).thenReturn(List.of(source("NEW",1000000L,1L,new BigDecimal(mm),new BigDecimal(days),new BigDecimal(hours))));
        when(mapper.vatRate("2026-10")).thenReturn(new BigDecimal("7.5"));
        var p=amounts.preview(new Scope(1,"2026-10",null));assertThat(p.sply_amt()).isEqualTo(expected);
        assertThat(p.suggested_vat_amt()).isEqualTo(new BigDecimal(expected).multiply(new BigDecimal("0.075")).longValue());
        assertThat(p.details().get(0).cntrct_dev_id()).isEqualTo(3);
        verify(mapper,never()).audit(any());
    }
    @Test void missingAttendanceExcludesLineButMissingPriceStillBlocksCreation() {
        when(mapper.project(1)).thenReturn(new Project(1,"파견","DP","DAY","Y"));
        when(mapper.sources(any())).thenReturn(List.of(source("NEW",null,null,BigDecimal.ONE,null,null)));
        var p=amounts.preview(new Scope(1,"2026-10",null));assertThat(p.details()).isEmpty();
        assertThat(p.warnings()).anyMatch(s->s.contains("근태 미입력"));assertThat(p.missing_price_assignments()).containsExactly(1L);
    }
    @Test void freeAssignmentsDoNotRequirePrice() {
        when(mapper.project(1)).thenReturn(new Project(1,"무상","MO","MONTH","Y"));
        when(mapper.sources(any())).thenReturn(List.of(source("FREE",null,null,BigDecimal.ONE,null,null)));
        var p=amounts.preview(new Scope(1,"2026-10",null));assertThat(p.sply_amt()).isZero();assertThat(p.details()).hasSize(1);assertThat(p.missing_price_assignments()).isEmpty();
    }
    @Test void internalProjectIsRejected() {
        when(mapper.project(1)).thenReturn(new Project(1,"내부","IN",null,"Y"));
        assertThatThrownBy(()->amounts.preview(new Scope(1,"2026-10",null))).isInstanceOf(com.sicost.common.api.ApiException.class);
    }
    @Test void filteredGapTotalAndDifferenceApplySamePredicate() {
        var project=new Project(1,"월단위","MO","MONTH","Y");
        when(mapper.lastClosedMonth()).thenReturn("2026-09");
        when(mapper.gapProjects("2026-09")).thenReturn(List.of(project));
        when(mapper.sources(any())).thenReturn(List.of(source("NEW",100000L,null,BigDecimal.ONE,null,null)));
        when(mapper.confirmed(any())).thenReturn(List.of(new Confirmed(7,80000),new Confirmed(8,-10000)));
        var gaps=new BillingGapService(mapper,amounts,clock,permissions);
        var result=gaps.list(new GapQuery(null,"적게 청구","월단위",BillingPaging.of(0,20,null)));
        assertThat(result.month()).isEqualTo("2026-09");assertThat(result.total()).isEqualTo(1);
        assertThat(result.total_difference_amt()).isEqualTo(30000);assertThat(result.items().get(0).confirmed_supply_amt()).isEqualTo(70000);
        assertThat(gaps.list(new GapQuery(null,"맞음",null,BillingPaging.of(0,20,null))).total()).isZero();
    }
}
