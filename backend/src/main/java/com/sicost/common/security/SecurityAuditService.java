package com.sicost.common.security;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityAuditService {
    public enum Event { LOGIN, LOGIN_FAIL, LOCK, UNLOCK, LOGOUT, AUTO_LOGOUT, PW_RESET, DOWNLOAD, UPLOAD, CLOSE, REOPEN, PERM_CHANGE, SETTLE_CONFIRM, SETTLE_CANCEL, CONTACT_VIEW, AUTHORIZATION_DENIED }
    public enum Purpose { STAFF_OPERATION, CONTRACT_SALES, BILLING, RECEIPT, PAYMENT, ACCOUNT_CORRECTION }
    public enum Result { SUCCESS, FAILURE, DENIED }
    private final SecurityAuditMapper mapper;
    private final Clock clock;
    private final AtomicLong failures=new AtomicLong();
    public SecurityAuditService(SecurityAuditMapper mapper,Clock clock) { this.mapper=mapper;this.clock=clock; }
    @Transactional public void record(Event event,String screen,Long target,Result result,Purpose purpose) {
        var principal=new CurrentUser().principal();
        append(principal.userId(),event,screen,target,result,purpose);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void denied(String screen,Long target) {
        var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        Long actor=authentication!=null && authentication.getPrincipal() instanceof SecurityPrincipal p?p.userId():null;
        append(actor,Event.AUTHORIZATION_DENIED,screen,target,Result.DENIED,null);
    }
    private void append(Long actor,Event event,String screen,Long target,Result result,Purpose purpose) {
        if(screen!=null && !screen.matches("UI-[0-9]{2}")) throw AuthorizationService.forbidden();
        String detail="{\"result_cd\":\""+result.name()+"\""+(purpose==null?"":",\"purpose_desc\":\""+purpose.name()+"\"")+"}";
        try { mapper.append(new SecurityAuditMapper.Entry(actor,event.name(),screen,target==null?null:target.toString(),detail,LocalDateTime.now(clock.withZone(SessionService.SEOUL)))); }
        catch(RuntimeException ex) {
            failures.incrementAndGet();
            LoggerFactory.getLogger(SecurityAuditService.class).error("SECURITY_AUDIT_WRITE_FAILED event={}",event.name());
            throw new com.sicost.common.api.ApiException(503,"AUDIT_UNAVAILABLE","접근 기록을 저장할 수 없습니다.",null);
        }
    }
    public long failureCount() { return failures.get(); }
}
