package com.sicost.businessplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.businessplan.BusinessPlanModels.*;

@Service
@Transactional(readOnly=true)
public class BusinessPlanService {
    private final BusinessPlanMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;
    private final Clock clock;
    public BusinessPlanService(BusinessPlanMapper mapper, CurrentUser user, PermissionService permissions,
            ObjectMapper json, Clock clock) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.json=json;this.clock=clock;
    }
    public Plans list(int year) {
        permissions.can("UI-18.read");
        return new Plans(mapper.list(year),mapper.parts(),mapper.closedMonths(year),year);
    }
    @Transactional
    public Plans save(int year,List<Plan> items) {
        permissions.can("UI-18.update");
        if(year<LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).getYear())
            throw ApiException.conflict("INVALID_MONTH","지난 연도는 조회만 가능합니다.","year");
        mapper.lockYear(year);mapper.lockMonths(year);
        var before=mapper.list(year);
        var closed=mapper.closedMonths(year);
        var parts=mapper.parts();
        for(Plan item:items) {
            if(item.year()!=year)throw BusinessPlanInput.invalid("year");
            var old=find(before,item);
            if(closed.contains(month(item))&&!same(old,item))
                throw ApiException.conflict("CLOSED_MONTH","마감 월의 목표는 변경할 수 없습니다.","month");
            if(item.partId()!=null) {
                var part=parts.stream().filter(p->p.id()==item.partId()).findFirst()
                    .orElseThrow(()->ApiException.notFound("영업 파트가 없습니다."));
                if(!part.active()&&old==null)throw BusinessPlanInput.invalid("partId");
            }
        }
        for(Plan item:items) {
            var old=find(before,item);
            if(same(old,item)||closed.contains(month(item)))continue;
            long id=mapper.upsert(new PlanWrite(item,month(item),user.userId()));
            mapper.audit(new PlanAudit(Long.toString(id),old==null?"INSERT":"UPDATE",snapshot(old),snapshot(item),user.userId()));
        }
        return new Plans(mapper.list(year),parts,closed,year);
    }
    private static boolean same(Plan a,Plan b) {
        return a!=null&&a.year()==b.year()&&a.month()==b.month()&&Objects.equals(a.partId(),b.partId())
            &&a.target_revenue()==b.target_revenue()&&a.target_operating_profit()==b.target_operating_profit()
            &&a.target_utilization_rate()!=null&&a.target_utilization_rate().compareTo(b.target_utilization_rate())==0;
    }
    private static Plan find(List<Plan> rows,Plan item) {
        return rows.stream().filter(p->p.month()==item.month()&&Objects.equals(p.partId(),item.partId())).findFirst().orElse(null);
    }
    public static String month(Plan plan){return YearMonth.of(plan.year(),plan.month()).toString();}
    private String snapshot(Object value) {
        if(value==null)return null;
        try{return json.writeValueAsString(value);}catch(java.io.IOException ex){throw new IllegalStateException(ex);}
    }
}
