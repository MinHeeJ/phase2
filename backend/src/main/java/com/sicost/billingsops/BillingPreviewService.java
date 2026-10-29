package com.sicost.billingsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.billing.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingReviewModels.*;

@Service
@Transactional(readOnly=true)
public class BillingPreviewService {
    private final BillingReviewMapper mapper;
    private final BillingAmountService amounts;
    private final Clock clock;
    public BillingPreviewService(BillingReviewMapper mapper,BillingAmountService amounts,Clock clock) {
        this.mapper=mapper; this.amounts=amounts; this.clock=clock;
    }
    public Preview preview(PreviewKey key) {
        if(key.projectId()<1) throw BillingRules.invalid("projectId");
        var project=mapper.project(key.projectId());
        if(project==null) throw ApiException.notFound("프로젝트가 없습니다.");
        BillingRules.billable(project.contract_type());
        if("TK".equals(project.contract_type())) {
            if(key.stageId()==null || key.stageId()<1) throw BillingRules.invalid("stageId");
            var stage=mapper.stage(key.stageId());
            if(stage==null || stage.project_id()!=key.projectId()) throw BillingRules.invalid("stageId");
            var month=stage.planned()==null ? YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))) : YearMonth.from(stage.planned());
            return new Preview(key.projectId(),key.stageId(),null,stage.sply_amt(),
                amounts.suggestedVat(stage.sply_amt(),month.toString()),null,List.of(),
                stage.planned()==null ? List.of("청구 예정일이 없습니다.") : List.of(),List.of());
        }
        BillingRules.month(key.month(),"month");
        if(key.stageId()!=null) throw BillingRules.invalid("stageId");
        var lines=new ArrayList<Line>(); var warnings=new ArrayList<String>(); var missing=new ArrayList<Long>();
        long supply=0;
        for(var source:mapper.sources(key)) {
            boolean free="FREE".equals(source.kind());
            if(!free && source.price()==null) { missing.add(source.asgn_id()); warnings.add(source.display_name()+": 단가가 없습니다. 청구 생성이 불가합니다."); continue; }
            boolean attendance="DP".equals(project.contract_type()) && Set.of("DAY","HOUR").contains(project.settlement());
            if(attendance && (source.attendance_id()==null || source.month_work_days()==null
                || ("DAY".equals(project.settlement()) ? source.work_day_qty()==null : source.regular_hours()==null))) {
                warnings.add(source.display_name()+": 근태 미입력 — 상세에서 제외했습니다."); continue;
            }
            BigDecimal mm=source.mm_qty();
            if(attendance) {
                if(source.work_day_qty()==null) { mm=null; warnings.add(source.display_name()+": 근무일수가 없어 산출 MM이 없습니다."); }
                else mm=amounts.attendanceMm(source.work_day_qty(),source.month_work_days());
            }
            BigDecimal quantity=attendance ? ("DAY".equals(project.settlement()) ? source.work_day_qty() : source.regular_hours()) : mm;
            if(quantity==null) { warnings.add(source.display_name()+": 월 MM이 없어 상세에서 제외했습니다."); continue; }
            long price=source.price()==null ? 0 : source.price();
            long amount=amounts.lineAmount(price,quantity,free);
            supply=BillingRules.add(supply,amount,"sply_amt");
            lines.add(new Line(source.asgn_id(),source.dev_id(),source.cntrct_dev_id(),source.display_name(),
                key.month(),mm,source.mm_qty(),source.price(),source.work_day_qty(),source.regular_hours(),
                source.overtime_hours(),0,amount,free ? "무상" : null));
        }
        return new Preview(key.projectId(),null,key.month(),supply,amounts.suggestedVat(supply,key.month()),
            null,List.copyOf(lines),List.copyOf(warnings),List.copyOf(missing));
    }
}
