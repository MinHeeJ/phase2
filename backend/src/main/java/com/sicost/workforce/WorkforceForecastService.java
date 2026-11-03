package com.sicost.workforce;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.PermissionService;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.workforce.WorkforceForecastModels.*;

@Service
@Transactional(readOnly=true)
public class WorkforceForecastService {
    private final WorkforceForecastMapper mapper;
    private final Clock clock;
    private final PermissionService permissions;
    public WorkforceForecastService(WorkforceForecastMapper mapper,Clock clock,PermissionService permissions){this.mapper=mapper;this.clock=clock;this.permissions=permissions;}
    private LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    private static BigDecimal display(BigDecimal n){return n.setScale(2,RoundingMode.HALF_UP);}
    public ForecastPage forecast(Map<String,String> parameters) {
        permissions.can("UI-17.read");var input=WorkforceForecastInput.parse(parameters,today());
        List<String> months=input.months().stream().map(YearMonth::toString).toList();
        var target=mapper.utilizationTarget(YearMonth.from(today()).toString());
        if(target==null)throw ApiException.badRequest("목표 가동률 운영 기준이 없습니다.","from");
        if(months.isEmpty())return new ForecastPage(Collections.emptyList(),input.page(),input.size(),0,Collections.emptyList(),months,target,"예측할 미래월이 없습니다.");
        var rows=new ArrayList<>(mapper.forecast(new Read(months.get(0),months.get(months.size()-1),input.job(),input.grade(),today())));
        Comparator<Source> order=switch(input.sort()) {
            case "job_cd"->Comparator.comparing(Source::job_cd);
            case "grd_cd"->Comparator.comparing(Source::grd_cd,Comparator.nullsFirst(Comparator.naturalOrder()));
            case "supply_mm"->Comparator.comparing(Source::supply_mm);
            case "demand_mm"->Comparator.comparing(Source::demand_mm);
            case "shortage_mm"->Comparator.comparing(s->s.demand_mm().subtract(s.supply_mm()).max(BigDecimal.ZERO));
            default->Comparator.comparing(Source::month);
        };
        if(input.descending())order=order.reversed();
        rows.sort(order.thenComparing(Source::month).thenComparing(Source::job_cd).thenComparing(r->Objects.toString(r.grd_cd(),"")));
        var shortages=new TreeMap<String,List<Source>>();
        for(var s:rows)if(s.demand_mm().subtract(s.supply_mm()).signum()>0)
            shortages.computeIfAbsent(s.job_cd()+":"+Objects.toString(s.grd_cd(),""),k->new ArrayList<>()).add(s);
        List<WorkforceShortage> warnings=new ArrayList<>();
        for(var group:shortages.values()) {
            group.sort(Comparator.comparing(Source::month));var first=group.get(0);var month=YearMonth.parse(first.month());
            var maximum=group.stream().map(s->s.demand_mm().subtract(s.supply_mm())).max(BigDecimal::compareTo).orElseThrow();
            if(maximum.compareTo(new BigDecimal("0.40"))<=0)continue;
            warnings.add(new WorkforceShortage(first.job_cd(),first.grd_cd(),display(maximum),maximum.setScale(0,RoundingMode.CEILING).intValueExact(),
                month.toString(),month.minusMonths(2).toString(),!month.minusMonths(2).isAfter(YearMonth.from(today())),
                mapper.candidateCount(new CandidateMatch(first.job_cd(),first.grd_cd(),YearMonth.parse(group.get(group.size()-1).month()).atEndOfMonth()))));
        }
        var items=new ArrayList<WorkforceMonth>();
        for(var s:rows) {
            var messages=new ArrayList<String>();messages.add("미래 영업이익 기준 원천 없음 — 시나리오는 증감만 비교");
            if(s.over_one_mm())messages.add("확정 MM 1.0 초과");if(s.missing_cost())messages.add("원가 미확인 — 비용은 확인된 원천만 사용");
            var remaining=s.supply_mm().subtract(s.demand_mm());
            items.add(new WorkforceMonth(s.month(),s.job_cd(),s.grd_cd(),display(s.supply_mm()),display(s.demand_mm()),display(remaining),display(remaining.negate().max(BigDecimal.ZERO)),
                display(s.available_mm()),display(s.confirmed_mm()),display(s.extension_mm()),display(s.temporary_mm()),
                s.available_mm().signum()==0?null:s.confirmed_mm().multiply(BigDecimal.valueOf(100)).divide(s.available_mm(),2,RoundingMode.HALF_UP),
                s.idle_cost().setScale(0,RoundingMode.FLOOR).longValueExact(),true,null,s.average_labor_cost().setScale(0,RoundingMode.FLOOR).longValueExact(),List.copyOf(messages)));
        }

        int start=(int)Math.min((long)input.page()*input.size(),items.size()),end=Math.min(start+input.size(),items.size());
        return new ForecastPage(List.copyOf(items.subList(start,end)),input.page(),input.size(),items.size(),List.copyOf(warnings),months,target,null);
    }
    public ShortagePage shortages(Map<String,String> parameters) {
        var source=new HashMap<>(parameters);
        String[] order=source.getOrDefault("sort","job_cd,asc").split(",",-1);
        if(order.length!=2||!Set.of("job_cd","grd_cd","maximum_shortage_mm","maximum_shortage_people","first_shortage_month","candidate_count").contains(order[0])||!Set.of("asc","desc").contains(order[1]))throw WorkforceForecastInput.invalid("sort");
        source.put("sort","month,asc");
        var input=WorkforceForecastInput.parse(source,today());var result=forecast(source);
        var rows=new ArrayList<>(result.shortages());
        Comparator<WorkforceShortage> comparator=switch(order[0]) {
            case "grd_cd"->Comparator.comparing(r->Objects.toString(r.grd_cd(),""));
            case "maximum_shortage_mm"->Comparator.comparing(WorkforceShortage::maximum_shortage_mm);
            case "maximum_shortage_people"->Comparator.comparingInt(WorkforceShortage::maximum_shortage_people);
            case "first_shortage_month"->Comparator.comparing(WorkforceShortage::first_shortage_month);
            case "candidate_count"->Comparator.comparingLong(WorkforceShortage::candidate_count);
            default->Comparator.comparing(WorkforceShortage::job_cd);
        };
        if(order[1].equals("desc"))comparator=comparator.reversed();
        rows.sort(comparator.thenComparing(WorkforceShortage::job_cd).thenComparing(r->Objects.toString(r.grd_cd(),"")));
        int start=(int)Math.min((long)input.page()*input.size(),rows.size()),end=Math.min(start+input.size(),rows.size());
        return new ShortagePage(List.copyOf(rows.subList(start,end)),input.page(),input.size(),rows.size());
    }
}
