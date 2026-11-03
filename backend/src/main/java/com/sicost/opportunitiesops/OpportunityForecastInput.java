package com.sicost.opportunitiesops;

import java.time.*;
import java.util.*;
import static com.sicost.opportunitiesops.OpportunityForecastModels.*;
import static com.sicost.opportunitiesops.OpportunityLifecycleInput.*;

public final class OpportunityForecastInput {
    private OpportunityForecastInput() {}
    public static Query parse(Map<String,String> p,Clock clock){
        var allowed=Set.of("from","months","job","grade","year","sort","page","size");
        p.keySet().forEach(k->{if(!allowed.contains(k))throw invalid(k);});
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));YearMonth current=YearMonth.from(today);
        String from=month(p.get("from"),"from");YearMonth start=from==null?current.plusMonths(1):YearMonth.parse(from);
        if(!start.isAfter(current))start=current.plusMonths(1);
        int count=integer(p,"months",6,1,6),year=integer(p,"year",0,1,9999);
        if(year>start.getYear())start=YearMonth.of(year,1);
        var months=new ArrayList<String>();for(int i=0;i<count;i++){var m=start.plusMonths(i);if(m.getYear()<=9999&&(year==0||m.getYear()==year))months.add(m.toString());}
        String sort=p.getOrDefault("sort","month,asc");
        if(!sort.matches("(month|project_revenue|opportunity_revenue|weighted_total|all_won_total),(asc|desc)"))throw invalid("sort");
        int size=integer(p,"size",50,20,100);if(!Set.of(20,50,100).contains(size))throw invalid("size");
        return new Query(List.copyOf(months),code(p.get("job"),"job"),code(p.get("grade"),"grade"),sort,integer(p,"page",0,0,Integer.MAX_VALUE),size,today);
    }
    private static String code(String s,String field){if(s==null||s.isBlank())return null;if(!s.matches("[A-Z0-9_]{1,30}"))throw invalid(field);return s;}
}
