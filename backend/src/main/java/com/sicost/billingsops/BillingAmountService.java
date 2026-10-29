package com.sicost.billingsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service("billingPreviewAmountService")
@Transactional(readOnly=true)
public class BillingAmountService {
    private final BillingMapper mapper;
    private final Clock clock;
    private final PermissionService permissions;
    public BillingAmountService(BillingMapper mapper, Clock clock, PermissionService permissions) {
        this.mapper=mapper;this.clock=clock;this.permissions=permissions;
    }
    public Preview preview(Scope scope) {
        permissions.can(BillingActions.READ);
        BillingInput.id(scope.projectId());
        Project p=mapper.project(scope.projectId());
        if(p==null)throw ApiException.notFound("프로젝트가 없습니다.");
        BillingRules.requireBillable(p.contract_type());
        if("TK".equals(p.contract_type())) {
            if(scope.stageId()==null)throw BillingValues.invalid("stageId");
            Stage s=mapper.stage(BillingInput.id(scope.stageId()));
            if(s==null || s.project_id()!=p.project_id())throw BillingValues.invalid("stageId");
            String month=s.bill_plan_dt()==null?YearMonth.from(ProjectValues.today(clock)).toString():YearMonth.from(s.bill_plan_dt()).toString();
            return new Preview(p.project_id(),s.bill_stg_id(),null,s.sply_amt(),
                BillingValues.vat(s.sply_amt(),mapper.vatRate(month),null),null,
                List.copyOf(new ArrayList<Line>()),List.copyOf(new ArrayList<String>()),List.copyOf(new ArrayList<Long>()));
        }
        String month=BillingValues.month(scope.month(),"month").toString();
        List<Line> lines=new ArrayList<>();List<String> warnings=new ArrayList<>();List<Long> missing=new ArrayList<>();
        long supply=0;
        for(Source s:mapper.sources(new Scope(p.project_id(),month,null))) {
            boolean free="FREE".equals(s.kind());
            if(!free && s.price()==null)missing.add(s.asgn_id());
            if(!"MONTH".equals(p.basis()) && (s.atnd_id()==null
                    || ("DAY".equals(p.basis())?s.work_day_qty()==null:s.work_tm_qty()==null))) {
                warnings.add(s.display_name()+": 근태 미입력");continue;
            }
            BigDecimal qty="MONTH".equals(p.basis())?s.source_mm_qty():
                "DAY".equals(p.basis())?s.work_day_qty():s.work_tm_qty();
            if(qty==null) {warnings.add(s.display_name()+": 투입 MM 미입력");continue;}
            if(!free && s.price()==null)continue;
            long amount=free?0:BillingValues.floorWon(qty.multiply(BigDecimal.valueOf(s.price())),"sply_amt");
            BigDecimal mm=s.source_mm_qty();
            if(!"MONTH".equals(p.basis())) {
                mm=s.work_day_qty()!=null && s.mth_work_day_qty()!=null && s.mth_work_day_qty().signum()>0
                    ?s.work_day_qty().divide(s.mth_work_day_qty(),2,RoundingMode.HALF_UP):null;
                if(mm==null)warnings.add(s.display_name()+": 산출 MM을 계산할 근무일수가 없습니다.");
            }
            lines.add(new Line(s.asgn_id(),s.dev_id(),s.cntrct_dev_id(),s.display_name(),month,
                mm,s.source_mm_qty(),s.price(),s.work_day_qty(),s.work_tm_qty(),s.ovtm_tm_qty(),0,amount,
                free?"무상지원":null));
            supply=BillingValues.add(supply,amount,"sply_amt");
        }
        if(!missing.isEmpty())warnings.add("청구 단가가 없는 투입은 자동 청구 생성이 불가합니다.");
        return new Preview(p.project_id(),null,month,supply,BillingValues.vat(supply,mapper.vatRate(month),null),
            null,List.copyOf(lines),List.copyOf(warnings),List.copyOf(missing));
    }
    public long sourceAmount(Project project, Source source) {
        if("FREE".equals(source.kind()))return 0;
        BigDecimal quantity="MONTH".equals(project.basis())?source.source_mm_qty():
            "DAY".equals(project.basis())?source.work_day_qty():source.work_tm_qty();
        return source.price()==null || quantity==null?0:
            BillingValues.floorWon(quantity.multiply(BigDecimal.valueOf(source.price())),"sply_amt");
    }
}
