package com.sicost.common.finance;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import java.time.*;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.common.finance.PaymentExecutionMapper.*;

@Service
public class PaymentExecutionService {
    private final PaymentExecutionMapper mapper;
    private final CurrentUser user;
    private final Clock clock;
    public PaymentExecutionService(PaymentExecutionMapper mapper,CurrentUser user,Clock clock) {
        this.mapper=mapper;this.user=user;this.clock=clock;
    }
    @Transactional
    public void completeTax(long taxPayId,LocalDate date,long total,LocalDateTime expected) {
        Long id=mapper.taxPaymentId(taxPayId);
        if(id==null) throw ApiException.conflict("STALE_PAYMENT","지급 관리에서 법인세 지급 후보를 생성하고 수령 계좌를 확인하세요.","tax_pay_id");
        var row=lock(id);
        long executed=mapper.executed(id);
        if(total<executed) throw ApiException.conflict("STALE_PAYMENT","실적 취소는 지급 관리의 완료 취소를 이용하세요.","actual_amount");
        completeLocked(row,date,Math.subtractExact(total,executed),expected,executed,true);
    }
    @Transactional
    public void complete(long id,LocalDate date,Long amount,LocalDateTime expected) {
        var row=lock(id);
        long executed=mapper.executed(id);
        completeLocked(row,date,amount==null?Math.subtractExact(row.planned_amount(),executed):amount,expected,executed,amount!=null);
    }
    private Payment lock(long id) {
        var row=mapper.lock(id);
        if(row==null) throw ApiException.notFound("지급 건이 없습니다.");
        return row;
    }
    private void completeLocked(Payment row,LocalDate date,long amount,LocalDateTime expected,long executed,boolean explicit) {
        if("PAID".equals(row.status())) throw ApiException.conflict("ALREADY_PAID","이미 지급 완료된 건입니다.","pay_id");
        if(!"PLAN".equals(row.status()) || row.parent_id()!=null || mapper.activeChild(row.pay_id()))
            throw ApiException.conflict("STALE_PAYMENT","이월 지급은 지급 관리에서 처리하세요.","pay_id");
        if(date==null || date.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))) throw invalid("actual_date");
        open(row.pay_ym());open(YearMonth.from(row.planned_date()).toString());open(YearMonth.from(date).toString());
        long remaining=Math.subtractExact(row.planned_amount(),executed);
        if(amount<0 || amount>remaining || (amount==0 && remaining!=0)) throw invalid("actual_amount");
        boolean partial=amount<remaining;
        if(partial && explicit && expected==null) throw invalid("updated_at");
        if(expected!=null && !Objects.equals(expected,row.updated_at())) throw ApiException.conflict("STALE_PAYMENT","다른 요청이 지급을 변경했습니다.","updated_at");
        if(blank(row.recipient()) || blank(row.bank()) || blank(row.account()) || blank(row.depositor())) throw invalid("account");
        var write=new Completion(row.pay_id(),date,amount,!partial,user.userId());
        mapper.insertExecution(write);
        if(mapper.updatePayment(write)!=1) throw ApiException.conflict("STALE_PAYMENT","지급 상태가 바뀌었습니다.","pay_id");
        mapper.synchronizeTax(write);mapper.audit(write);
    }
    private boolean blank(String value){return value==null || value.isBlank();}
    private ApiException invalid(String field){return new ApiException(400,"VALIDATION_ERROR","입력값을 확인하세요.",field);}
    private void open(String month) {
        if("CLOSED".equals(mapper.monthStatus(month))) throw ApiException.conflict("CLOSED_MONTH",month+"은 마감되었습니다.","month");
    }
}
