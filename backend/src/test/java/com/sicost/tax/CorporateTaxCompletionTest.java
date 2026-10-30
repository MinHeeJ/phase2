package com.sicost.tax;

import com.sicost.common.api.ApiException;
import com.sicost.common.finance.*;
import com.sicost.common.security.CurrentUser;
import java.time.*;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.common.finance.PaymentExecutionMapper.*;

class CorporateTaxCompletionTest {
    PaymentExecutionMapper mapper;
    PaymentExecutionService service;
    LocalDate date=LocalDate.of(2026,10,15);
    LocalDateTime version=LocalDateTime.of(2026,10,14,12,0);
    Payment row(String status) {return new Payment(7,"2026-10",date,1000000,status,"AUTO_FIN","CORP_TAX",55L,null,version,"납부 기관","BANK","1234567890","납부 기관");}
    @BeforeEach void setup() {
        mapper=mock(PaymentExecutionMapper.class);
        service=new PaymentExecutionService(mapper,new CurrentUser(),Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        when(mapper.taxPaymentId(55)).thenReturn(7L);when(mapper.lock(7)).thenReturn(row("PLAN"));
        when(mapper.updatePayment(any())).thenReturn(1);
    }
    @Test void taxCompletionWritesExactlyOneExecutionAndSynchronizesTaxWithoutChangingPlanAmount() {
        service.completeTax(55,date,1000000,null);
        var write=new Completion(7,date,1000000,true,null);
        verify(mapper).insertExecution(write);verify(mapper).updatePayment(write);verify(mapper).synchronizeTax(write);verify(mapper).audit(write);
        assertThat(row("PLAN").planned_amount()).isEqualTo(1000000);
    }
    @Test void partialPaymentUsesDeltaAndLeavesParentPlan() {
        service.completeTax(55,date,400000,version);
        verify(mapper).insertExecution(new Completion(7,date,400000,false,null));
    }
    @Test void partialRequiresVersion() {
        assertThatThrownBy(()->service.completeTax(55,date,400000,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.field()).isEqualTo("updated_at"));untouched();
    }
    @Test void staleVersionCannotRepeatPartial() {
        when(mapper.executed(7)).thenReturn(400000L);
        assertThatThrownBy(()->service.completeTax(55,date,600000,version.minusSeconds(1))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("STALE_PAYMENT"));untouched();
    }
    @Test void alreadyPaidPrecedesStaleVersion() {
        when(mapper.lock(7)).thenReturn(row("PAID"));
        assertThatThrownBy(()->service.completeTax(55,date,1000000,version.minusDays(1))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ALREADY_PAID"));untouched();
    }
    @Test void closedOriginalMonthIsProtected() {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        assertThatThrownBy(()->service.completeTax(55,date,1000000,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));untouched();
    }
    @Test void closedActualMonthIsProtected() {
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        assertThatThrownBy(()->service.completeTax(55,LocalDate.of(2026,9,30),1000000,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));untouched();
    }
    @Test void futureActualDateIsRejectedUsingSeoulClock() {
        assertThatThrownBy(()->service.completeTax(55,LocalDate.of(2026,10,31),1000000,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.field()).isEqualTo("actual_date"));untouched();
    }
    @Test void excessActualNeverWrites() {
        assertThatThrownBy(()->service.completeTax(55,date,1000001,null)).isInstanceOf(ApiException.class);untouched();
    }
    @Test void unlinkedTaxDoesNotInventRecipientOrPayment() {
        when(mapper.taxPaymentId(55)).thenReturn(null);
        assertThatThrownBy(()->service.completeTax(55,date,1000000,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("STALE_PAYMENT"));untouched();
    }
    @Test void carryChainCannotCompleteRootAgain() {
        when(mapper.activeChild(7)).thenReturn(true);
        assertThatThrownBy(()->service.completeTax(55,date,1000000,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("STALE_PAYMENT"));untouched();
    }
    @Test void missingBankDetailsNeverCompletes() {
        var row=row("PLAN");
        when(mapper.lock(7)).thenReturn(new Payment(7,row.pay_ym(),date,1000000,"PLAN","AUTO_FIN","CORP_TAX",55L,null,version,"기관",null,null,null));
        assertThatThrownBy(()->service.completeTax(55,date,1000000,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.field()).isEqualTo("account"));untouched();
    }
    void untouched(){verify(mapper,never()).insertExecution(any());verify(mapper,never()).updatePayment(any());verify(mapper,never()).synchronizeTax(any());verify(mapper,never()).audit(any());}
}
