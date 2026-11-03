package com.sicost.workforce;

import com.sicost.common.api.ApiException;
import java.time.*;
import java.util.*;

public record WorkforceForecastInput(List<YearMonth> months,String job,String grade,int page,int size,String sort,boolean descending) {
    public static ApiException invalid(String key){return ApiException.badRequest("예측 조회 조건을 확인하세요.",key);}
    public static WorkforceForecastInput parse(Map<String,String> p,LocalDate today) {
        var allowed=Set.of("from","months","job","grade","year","sort","page","size");
        for(String k:p.keySet())if(!allowed.contains(k))throw invalid(k);
        int page=number(p,"page",0),size=number(p,"size",50),count=number(p,"months",6);
        if(page<0||page>Integer.MAX_VALUE/100)throw invalid("page");if(!Set.of(20,50,100).contains(size))throw invalid("size");
        if(count<1||count>6)throw invalid("months");
        YearMonth current=YearMonth.from(today),start=current.plusMonths(1);Integer year=null;
        if(p.containsKey("from")){try{if(!p.get("from").matches("[0-9]{4}-(0[1-9]|1[0-2])"))throw invalid("from");start=YearMonth.parse(p.get("from"));}catch(RuntimeException e){throw invalid("from");}if(!start.isAfter(current))throw invalid("from");}
        if(p.containsKey("year")){year=number(p,"year",0);if(year<1||year>9999)throw invalid("year");start=YearMonth.of(year,1);if(!start.isAfter(current))start=current.plusMonths(1);}
        var months=new ArrayList<YearMonth>();for(int i=0;i<count;i++){var m=start.plusMonths(i);if(year==null||m.getYear()==year)months.add(m);}
        String job=code(p,"job"),grade=code(p,"grade");String[] sort=p.getOrDefault("sort","month,asc").split(",",-1);
        if(sort.length!=2||!Set.of("month","job_cd","grd_cd","supply_mm","demand_mm","shortage_mm").contains(sort[0])||!Set.of("asc","desc").contains(sort[1]))throw invalid("sort");
        return new WorkforceForecastInput(List.copyOf(months),job,grade,page,size,sort[0],sort[1].equals("desc"));
    }
    public static int number(Map<String,String> p,String k,int d){try{return p.containsKey(k)?Integer.parseInt(p.get(k)):d;}catch(RuntimeException e){throw invalid(k);}}
    private static String code(Map<String,String> p,String k){String v=p.get(k);if(v==null||v.isBlank())return null;if(!v.matches("[A-Z0-9_]{1,30}"))throw invalid(k);return v;}
}
