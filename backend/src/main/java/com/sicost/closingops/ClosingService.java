package com.sicost.closingops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.apiops.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.validation.ClosingValues;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.sicost.closingops.ClosingModels.*;
import static com.sicost.closingops.ClosingSnapshotService.conflict;

@Service
public class ClosingService {
    private final ClosingMapper mapper;
    private final ClosingCheckService checks;
    private final ClosingCheckWriter writer;
    private final ClosingSnapshotService snapshots;
    private final TransactionTemplate transaction;
    private final CurrentUser user;
    @Autowired private Clock clock;
    @Autowired private ClosingQueryService stored;
    @Autowired private ClosingQueryMapper evidence;
    @Autowired private ObjectMapper json;
    public ClosingService(ClosingMapper mapper,ClosingCheckService checks,ClosingCheckWriter writer,
            ClosingSnapshotService snapshots,PlatformTransactionManager transactions,CurrentUser user) {
        this.mapper=mapper;this.checks=checks;this.writer=writer;this.snapshots=snapshots;this.user=user;
        transaction=new TransactionTemplate(transactions);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private LocalDateTime now() {return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    public CheckResult check(String requested) {
        String month=ClosingValues.confirmMonth(requested,clock).toString();
        writer.ensureMonth(month);
        return transaction.execute(tx->{mapper.lockMonths();open(month,"CLOSED_MONTH");return run(month);});
    }
    private CheckResult run(String month) {
        var results=checks.calculate(month);var at=now();
        writer.save(new CheckRun(month,results,writer.encode(results),user.userId(),at));
        return new CheckResult(month,"OPEN",results,timestamp(at));
    }
    private void open(String month,String code) {
        if("CLOSED".equals(mapper.status(month)))throw conflict(code,"이미 마감한 월입니다.");
    }
    private void order(String month) {
        var closed=mapper.closedMonths();
        for(int i=1;i<closed.size();i++)if(!YearMonth.parse(closed.get(i-1)).plusMonths(1).toString().equals(closed.get(i)))throw conflict("CLOSE_ORDER","마감 월의 연속 순서를 확인하세요.");
        if(!closed.isEmpty()&&!YearMonth.parse(closed.get(closed.size()-1)).plusMonths(1).toString().equals(month))throw conflict("CLOSE_ORDER","가장 최근 마감의 다음 월만 확정할 수 있습니다.");
    }
    public Result confirm(String requested,Boolean ackWarnings) {
        if(ackWarnings==null)throw new ApiException(400,"VALIDATION_ERROR","경고 확인 여부는 필수입니다.","ackWarnings");
        String month=ClosingValues.confirmMonth(requested,clock).toString();
        writer.ensureMonth(month);
        return transaction.execute(tx->{
            mapper.lockMonths();open(month,"ALREADY_CLOSED");order(month);
            var checked=run(month);
            if(checked.checks().stream().anyMatch(c->c.status().equals("FAIL")))throw conflict("CLOSE_CHECK_FAILED","실패한 점검을 해결한 뒤 마감하세요.");
            if(!ackWarnings&&checked.checks().stream().anyMatch(c->c.status().equals("WARN")))throw conflict("CLOSE_CHECK_FAILED","경고 내용을 확인하고 동의해 주세요.");
            var components=snapshots.components(month);var at=now();
            var write=new SnapshotWrite(components,user.userId(),at);
            components.allocations().forEach(mapper::allocation);
            mapper.profit(write);components.details().forEach(d->mapper.detail(new DetailWrite(month,d)));
            mapper.tax(write);mapper.cash(write);
            var transition=new Transition(month,"CLOSE",null,user.userId(),at);
            if(mapper.transition(transition)!=1)throw conflict("ALREADY_CLOSED","이미 마감한 월입니다.");
            mapper.historyWrite(transition);
            var audit=new Audit(transition,manifest(month));mapper.audit(audit);mapper.access(audit);
            stored.getClosing(month);
            return new Result(month,"CLOSED",timestamp(at),actor());
        });
    }
    private String actor() {
        String name=user.userId()==null?null:mapper.actor(user.userId());return name==null?"시스템 처리":name;
    }
    private String manifest(String month) {
        try {return json.writeValueAsString(Map.of("snapshots",json.readTree(evidence.snapshotEvidence(month))));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException(ex);}
    }
    public Result reopen(String requested,String requestedReason) {
        String month=ClosingValues.month(requested,"month").toString();String reason=reason(requestedReason);
        return transaction.execute(tx->{
            mapper.lockMonths();var closed=mapper.closedMonths();
            if(!"CLOSED".equals(mapper.status(month))||closed.isEmpty()||!closed.get(closed.size()-1).equals(month))throw conflict("CLOSE_ORDER","가장 최근 마감 월만 해제할 수 있습니다.");
            stored.getClosing(month);
            if(evidence.activeReferences(month)>0)throw conflict("ADJUSTMENT_REFERENCE_EXISTS","유효한 전월 조정 참조가 있습니다.");
            mapper.deleteAllocations(month);mapper.deleteProfit(month);mapper.deleteTax(month);mapper.clearCash(month);
            var at=now();var transition=new Transition(month,"REOPEN",reason,user.userId(),at);
            if(mapper.transition(transition)!=1)throw conflict("CLOSE_ORDER","마감 상태가 변경되었습니다.");
            mapper.historyWrite(transition);var audit=new Audit(transition,writer.encode(transition));mapper.audit(audit);mapper.access(audit);
            return new Result(month,"OPEN",timestamp(at),actor());
        });
    }
    public static String reason(String value) {
        if(value==null||value.trim().isEmpty()||value.trim().length()>500)throw new ApiException(400,"VALIDATION_ERROR","사유를 1~500자로 입력하세요.","reason");
        return value.trim();
    }
}
