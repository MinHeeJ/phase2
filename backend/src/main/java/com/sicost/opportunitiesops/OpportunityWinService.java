package com.sicost.opportunitiesops;

import com.sicost.common.api.ApiException;
import com.sicost.projects.ProjectModels.ProjectSave;
import com.sicost.projects.ProjectService;
import com.sicost.assignments.AssignmentModels.*;
import com.sicost.assignments.AssignmentService;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.opportunitiesops.OpportunityLifecycleModels.*;

@Service
public class OpportunityWinService {
    private final OpportunityLifecycleMapper mapper;
    private final OpportunityLifecycleService lifecycle;
    private final OpportunityQuoteVerifier quotes;
    private final ProjectService projects;
    private final AssignmentService assignments;
    public OpportunityWinService(OpportunityLifecycleMapper mapper,OpportunityLifecycleService lifecycle,
        OpportunityQuoteVerifier quotes,ProjectService projects,AssignmentService assignments){
        this.mapper=mapper;this.lifecycle=lifecycle;this.quotes=quotes;this.projects=projects;this.assignments=assignments;
    }
    @Transactional
    public WinResult win(long id,ProjectSave project){
        var row=lifecycle.open(id,"confirm");mapper.lockNeeds(id);var needs=mapper.needs(id);
        var s=quotes.calculate(row,needs,lifecycle.today());
        BigDecimal threshold=mapper.approvalRate(YearMonth.from(lifecycle.today()).toString());
        if(threshold==null)throw ApiException.conflict("QUOTE_CRITERIA_MISSING","견적 승인 기준값을 확인하세요.",null);
        if(!quotes.current(row,s))throw ApiException.conflict("QUOTE_CHANGED","견적 원천이 변경되었습니다. 재계산하세요.","needs");
        if("UNKNOWN".equals(quotes.costStatus(row,s)))throw ApiException.conflict("COST_UNVERIFIED","원가 자료 등록과 견적 재계산이 필요합니다.","needs");
        boolean exceptional=s.margin()==null||s.margin().compareTo(threshold)<0||s.cost()==0;
        if("PENDING".equals(row.approval())||"REJECTED".equals(row.approval())||exceptional&&!"APPROVED".equals(row.approval()))
            throw ApiException.conflict("QUOTE_APPROVAL_REQUIRED","견적 별도 승인이 필요합니다.","quote");
        if(s.revenue()<1||!"IN".equals(project.cntrct_ty_cd())&&!Objects.equals(project.init_cntrct_amt(),s.revenue()))
            throw ApiException.conflict("QUOTE_AMOUNT_MISMATCH","최종 견적금액과 계약금액이 일치해야 합니다.","init_cntrct_amt");
        if(!Objects.equals(row.cntrct_ty_cd(),project.cntrct_ty_cd())||
            !"IN".equals(project.cntrct_ty_cd())&&(!Objects.equals(row.cust_ptnr_id(),project.cust_ptnr_id())||!Objects.equals(row.bsn_part_id(),project.bsn_part_id())))
            throw OpportunityLifecycleInput.invalid("project");
        YearMonth start=YearMonth.from(project.cntrct_bgn_dt()),end=YearMonth.from(project.cntrct_end_dt());
        var closed=mapper.closedMonths();
        for(var need:needs){
            var b=YearMonth.parse(need.bgn_ym());var e=b.plusMonths(need.mth_cnt()-1L);
            if(b.isBefore(start)||e.isAfter(end))throw ApiException.conflict("PLAN_OUTSIDE_CONTRACT","필요 인력 계획이 계약기간 밖에 있습니다. 필요 행을 직접 수정하세요.","needs");
            for(var m=b;!m.isAfter(e);m=m.plusMonths(1))if(closed.contains(m.toString()))throw ApiException.conflict("MONTH_CLOSED","마감 월 계획은 변경할 수 없습니다.","needs");
        }
        var temporary=mapper.temporary(id);
        var created=projects.create(project);long projectId=created.row().prj_id();
        mapper.link(new Link(id,projectId,lifecycle.actor()));
        for(var need:needs){
            BigDecimal mm=BigDecimal.valueOf(need.psn_cnt()).multiply(need.mth_mm_qty());
            Long cost=need.psn_mth_cst_amt()==null?null:mm.multiply(BigDecimal.valueOf(need.psn_mth_cst_amt())).setScale(0,RoundingMode.DOWN).longValueExact();
            for(int i=0;i<need.mth_cnt();i++)mapper.plan(new Plan(projectId,need,YearMonth.parse(need.bgn_ym()).plusMonths(i).toString(),mm,cost,lifecycle.actor()));
        }
        List<SaveResult> converted=new ArrayList<>();
        for(var t:temporary){
            var b=YearMonth.parse(t.bgn_ym()).atDay(1);var e=YearMonth.parse(t.end_ym()==null?t.bgn_ym():t.end_ym()).atEndOfMonth();
            if(b.isBefore(project.cntrct_bgn_dt()))b=project.cntrct_bgn_dt();if(e.isAfter(project.cntrct_end_dt()))e=project.cntrct_end_dt();
            if(b.isAfter(e))throw assignmentConflict();
            try {
                var result=assignments.create(new CreateInput(projectId,t.dev_id(),t.job_cd(),b,e,t.mm_qty(),t.price(),
                    project.stlm_mthd_cd(),"NEW",null,null,null,null,null));
                converted.add(result);mapper.convert(new Conversion(t.id(),result.assignment().item().row().asgn_id(),lifecycle.actor()));
            }catch(ApiException ex){throw assignmentConflict();}
        }
        lifecycle.change(row,Map.of("oppt_stts_cd",row.oppt_stts_cd(),"bsn_stg_cd",row.bsn_stg_cd()),
            Map.of("oppt_stts_cd","WON","bsn_stg_cd","WON","win_prj_id",projectId,"end_dt",lifecycle.today()));
        return new WinResult(lifecycle.detail(id),created,List.copyOf(converted));
    }
    private ApiException assignmentConflict(){return ApiException.conflict("ASSIGNMENT_CONFLICT","가배정의 기간·중복·단가 또는 소급 사유를 확인하세요. 수주는 반영되지 않았습니다.","project");}
}
