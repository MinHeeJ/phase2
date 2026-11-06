package com.sicost.opportunitiesops;

import com.sicost.common.api.*;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.security.PermissionService;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.opportunitiesops.OpportunityLifecycleModels.*;

@Service
@Transactional(readOnly=true)
public class OpportunityLifecycleService {
    private final OpportunityLifecycleMapper mapper;
    private final OpportunityQuoteVerifier quotes;
    private final CurrentUser user;
    private final Clock clock;
    private final PermissionService permissions;
    public OpportunityLifecycleService(OpportunityLifecycleMapper mapper,OpportunityQuoteVerifier quotes,CurrentUser user,Clock clock,PermissionService permissions){
        this.mapper=mapper;this.quotes=quotes;this.user=user;this.clock=clock;this.permissions=permissions;
    }
    LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    Long actor(){return user.userId();}
    Row open(long id,String action){
        var scope=permissions.scope("UI-29."+action);
        var row=mapper.lockScoped(id,scope);if(row==null)throw ApiException.notFound("기회가 없습니다.");
        if(!"OPEN".equals(row.oppt_stts_cd()))throw ApiException.conflict("OPPORTUNITY_CLOSED","종료된 기회는 변경할 수 없습니다.","id");return row;
    }
    public Detail detail(long id){
        var row=mapper.row(id);if(row==null)throw ApiException.notFound("기회가 없습니다.");
        var needs=mapper.needs(id);var s=quotes.calculate(row,needs,today());
        String cost=quotes.costStatus(row,s);List<String> warnings=new ArrayList<>();
        if(cost.equals("UNKNOWN"))warnings.add("원가 미확인 · 승인 후에도 원가 자료 등록과 재계산이 필요합니다.");
        if(!quotes.current(row,s))warnings.add("견적 원천이 변경되었습니다. 기회를 다시 저장하여 재계산하세요.");
        long difference=Math.subtractExact(s.revenue(),row.expc_amt());
        var rate=com.sicost.opportunities.OpportunityQuoteCalculator.rate(BigDecimal.valueOf(difference),row.expc_amt());
        Long approved="APPROVED".equals(row.approval())&&quotes.current(row,s)?s.revenue():null;
        var quote=new Quote(s.revenue(),s.cost(),s.margin(),s.cost(),difference,rate,cost,row.approval(),approved,List.copyOf(warnings));
        boolean stale="OPEN".equals(row.oppt_stts_cd())&&java.time.temporal.ChronoUnit.DAYS.between(
            (row.updated_at()==null?row.created_at():row.updated_at()).toLocalDate(),today())>14;
        var rows=needs.stream().map(n->new NeedDetail(n,Collections.emptyList())).toList();
        return new Detail(row,rows,quote,mapper.history(id),stale,List.copyOf(warnings));
    }
    void change(Row row,Map<String,Object> before,Map<String,Object> values){
        var original=new LinkedHashMap<String,Object>(mapper.auditValues(row.oppt_id()));
        original.putAll(before);
        mapper.change(new Change(row.oppt_id(),values,user.userId()));
        values.forEach((k,v)->{if(!Objects.equals(original.get(k),v))mapper.audit(new Audit("bsn.oppt",Long.toString(row.oppt_id()),k,masked(original.get(k)),masked(v),user.userId()));});
    }
    private static String masked(Object v){
        if(v==null)return null;return v.toString().replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","[연락처 마스킹]")
            .replaceAll("(?<![0-9])[0-9]{2,3}[- .]?[0-9]{3,4}[- .]?[0-9]{4}(?![0-9])","[연락처 마스킹]");
    }
    @Transactional
    public Detail stage(long id,String stage){
        var row=open(id,"update");
        if("PENDING".equals(row.approval())&&(Set.of("NEGO","PRE").contains(stage)||"PRE".equals(row.bsn_stg_cd())))
            throw ApiException.conflict("QUOTE_APPROVAL_REQUIRED","견적 승인 후 단계를 변경하세요.","bsn_stg_cd");
        var probability=switch(stage){case "DISC"->10;case "PROP"->30;case "NEGO"->60;case "PRE"->90;default->throw OpportunityLifecycleInput.invalid("bsn_stg_cd");};
        change(row,Map.of("bsn_stg_cd",row.bsn_stg_cd(),"win_prblt_rt",row.win_prblt_rt()),Map.of("bsn_stg_cd",stage,"win_prblt_rt",BigDecimal.valueOf(probability)));
        return detail(id);
    }
    @Transactional
    public Detail approve(long id,Approval input){return approval(id,input,false);}
    @Transactional
    public Detail reject(long id,String reason){return approval(id,new Approval(reason,null),true);}
    private Detail approval(long id,Approval input,boolean reject){
        var row=open(id,"confirm");
        if(!"PENDING".equals(row.approval()))throw ApiException.conflict("QUOTE_ALREADY_PROCESSED","승인 대기 견적만 처리할 수 있습니다.","id");
        var s=quotes.calculate(row,mapper.needs(id),today());
        if(!quotes.current(row,s))throw ApiException.conflict("QUOTE_CHANGED","견적 원천이 변경되었습니다. 다시 저장하여 승인 요청하세요.","needs");
        String evidence=input.zero_cost_evidence();
        if(evidence!=null&&!quotes.zeroProof(s,evidence))throw OpportunityLifecycleInput.invalid("zero_cost_evidence");
        Map<String,Object> values=new LinkedHashMap<>(),before=new LinkedHashMap<>();
        before.put("quot_aprv_stts_cd",row.approval());before.put("aprv_mrgn_rt",row.approved_margin());before.put("aprv_at",row.approved_at());
        before.put("rjct_rsn_desc",row.rjct_rsn_desc());before.put("zero_cost_evidence",row.evidence());before.put("zero_cost_validation",row.validation());
        values.put("quot_aprv_stts_cd",reject?"REJECTED":"APPROVED");values.put("aprv_mrgn_rt",s.margin());
        values.put("aprv_user_id",user.userId());values.put("aprv_at",LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        values.put("rjct_rsn_desc",reject?input.opinion():null);
        values.put("zero_cost_evidence",reject?null:evidence);values.put("zero_cost_validation",!reject&&evidence!=null?s.fingerprint():null);
        change(row,before,values);
        mapper.approval(new ApprovalWrite(id,reject?"REJECT":"APPROVE",input.opinion(),s.margin(),s.revenue(),user.userId()));
        return detail(id);
    }
    @Transactional
    public Detail lose(long id,String reason){return close(id,"LOST",reason);}
    @Transactional
    public Detail disable(long id){return close(id,"DELETED",null);}
    private Detail close(long id,String status,String reason){
        var row=open(id,status.equals("DELETED")?"delete":"update");mapper.lockNeeds(id);
        var before=new LinkedHashMap<String,Object>();before.put("oppt_stts_cd",row.oppt_stts_cd());before.put("bsn_stg_cd",row.bsn_stg_cd());before.put("lost_rsn_desc",row.lost_rsn_desc());
        var values=new LinkedHashMap<String,Object>();values.put("oppt_stts_cd",status);values.put("end_dt",today());
        if(status.equals("LOST")){values.put("bsn_stg_cd","LOST");values.put("lost_rsn_desc",reason);}
        change(row,before,values);mapper.release(new Change(id,Collections.emptyMap(),user.userId()));return detail(id);
    }
}
