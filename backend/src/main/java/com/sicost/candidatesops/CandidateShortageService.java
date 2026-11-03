package com.sicost.candidatesops;

import com.sicost.common.api.ApiException;
import com.sicost.common.workforce.CandidateForecastReader;
import com.sicost.developers.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.candidatesops.CandidateContactModels.*;

@Service
@Transactional(readOnly=true)
public class CandidateShortageService {
    private final CandidateContactMapper mapper;
    private final DeveloperMapper codes;
    private final ObjectProvider<CandidateForecastReader> forecast;
    private final Clock clock;
    public CandidateShortageService(CandidateContactMapper mapper, DeveloperMapper codes,
            ObjectProvider<CandidateForecastReader> forecast, Clock clock) {
        this.mapper=mapper;this.codes=codes;this.forecast=forecast;this.clock=clock;
    }
    public ShortagePage shortages(Map<String,String> parameters) {
        var q=CandidateContactInput.query(parameters,clock);
        if(q.job()!=null && !codes.activeCode(new DeveloperModels.DeveloperCode("JOB",q.job())))throw CandidateContactInput.invalid("job");
        if(q.grade()!=null && !codes.activeCode(new DeveloperModels.DeveloperCode("GRADE",q.grade())))throw CandidateContactInput.invalid("grade");
        var reader=forecast.getIfAvailable();
        if(reader==null)throw new ApiException(500,"FORECAST_UNAVAILABLE","공통 인력 예측 서비스를 사용할 수 없습니다.",null);
        YearMonth current=YearMonth.from(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        YearMonth last=q.from().plusMonths(q.months()-1), horizon=current.plusMonths(6);
        record Bucket(String job,String grade) {}
        Map<Bucket,List<CandidateForecastReader.Month>> grouped=new LinkedHashMap<>();
        for(var month:reader.candidateMonths(q.forecastFilters())) {
            var ym=YearMonth.parse(month.month());
            if(!ym.isAfter(current)||ym.isAfter(horizon)||ym.isBefore(q.from())||ym.isAfter(last)
                    ||q.year()!=null && ym.getYear()!=q.year())continue;
            if(q.job()!=null&&!q.job().equals(month.job_cd())||q.grade()!=null&&!q.grade().equals(month.grd_cd()))continue;
            grouped.computeIfAbsent(new Bucket(month.job_cd(),month.grd_cd()),k->new ArrayList<>()).add(month);
        }
        List<Shortage> all=new ArrayList<>();
        for(var entry:grouped.entrySet()) {
            var deficient=entry.getValue().stream().filter(m->m.shortage_mm().compareTo(new BigDecimal("0.40"))>0).toList();
            if(deficient.isEmpty())continue;
            BigDecimal max=entry.getValue().stream().map(CandidateForecastReader.Month::shortage_mm).max(Comparator.naturalOrder()).orElseThrow();
            YearMonth first=deficient.stream().map(m->YearMonth.parse(m.month())).min(Comparator.naturalOrder()).orElseThrow(), hire=first.minusMonths(2);
            var key=entry.getKey();
            long count=mapper.candidateCount(new CandidateCount(key.job(),q.grade()!=null?q.grade():key.grade(),
                deficient.stream().map(m->YearMonth.parse(m.month()).atEndOfMonth()).distinct().toList()));
            all.add(new Shortage(key.job(),key.grade(),max,max.setScale(0,RoundingMode.CEILING).intValueExact(),
                first.toString(),hire.toString(),hire.isBefore(current),count));
        }
        Comparator<Shortage> order=switch(q.sort().split(",")[0]) {
            case "maximum_shortage_mm" -> Comparator.comparing(Shortage::maximum_shortage_mm);
            case "maximum_shortage_people" -> Comparator.comparingInt(Shortage::maximum_shortage_people);
            case "first_shortage_month" -> Comparator.comparing(Shortage::first_shortage_month);
            case "recommended_hiring_month" -> Comparator.comparing(Shortage::recommended_hiring_month);
            case "candidate_count" -> Comparator.comparingLong(Shortage::candidate_count);
            case "grd_cd" -> Comparator.comparing(Shortage::grd_cd,Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(Shortage::job_cd);
        };
        if(q.sort().endsWith(",desc"))order=order.reversed();
        all.sort(order.thenComparing(Shortage::job_cd).thenComparing(Shortage::grd_cd,Comparator.nullsLast(Comparator.naturalOrder())));
        return new ShortagePage(all.stream().skip((long)q.page()*q.size()).limit(q.size()).toList(),q.page(),q.size(),all.size());
    }
}
