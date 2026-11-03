package com.sicost.candidatesops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.*;
import java.util.*;
import static com.sicost.candidatesops.CandidateContactModels.*;

public final class CandidateContactInput {
    private CandidateContactInput() {}
    public static ApiException invalid(String field) { return ApiException.badRequest("입력값을 확인하세요.",field); }
    public static long id(long id) { if(id<1)throw invalid("id");return id; }
    public static void fields(JsonNode n, Set<String> allowed) {
        if(n==null || !n.isObject())throw invalid(null);
        n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid(k);});
    }
    public static ContactInput contact(JsonNode n, Clock clock) {
        fields(n,Set.of("cntct_dt","cntct_stts_cd","memo"));
        LocalDate date;
        try {
            if(!n.path("cntct_dt").isTextual() || !n.path("cntct_dt").asText().matches("\\d{4}-\\d{2}-\\d{2}"))throw invalid("cntct_dt");
            date=LocalDate.parse(n.path("cntct_dt").asText());
        } catch(DateTimeException e) { throw invalid("cntct_dt"); }
        if(date.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))))throw invalid("cntct_dt");
        if(!n.path("cntct_stts_cd").isTextual() || !Set.of("NONE","DONE","INTV","HOLD","SIGNED").contains(n.path("cntct_stts_cd").asText()))throw invalid("cntct_stts_cd");
        var memo=n.get("memo");
        if(memo==null || !memo.isTextual() || memo.asText().isBlank() || memo.asText().codePointCount(0,memo.asText().length())>1000)throw invalid("memo");
        return new ContactInput(date,n.path("cntct_stts_cd").asText(),memo.asText());
    }
    public record ShortageQuery(YearMonth from, int months, String job, String grade, Integer year,
            String sort, int page, int size) {
        public Map<String,String> forecastFilters() {
            var values=new LinkedHashMap<String,String>();
            values.put("from",from.toString());values.put("months",Integer.toString(months));
            if(job!=null)values.put("job",job);if(grade!=null)values.put("grade",grade);
            if(year!=null)values.put("year",year.toString());
            return values;
        }
    }
    public static ShortageQuery query(Map<String,String> p, Clock clock) {
        var allowed=Set.of("from","months","job","grade","year","sort","page","size");
        p.keySet().forEach(k->{if(!allowed.contains(k))throw invalid(k);});
        YearMonth next=YearMonth.from(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))).plusMonths(1), from=next;
        if(p.containsKey("from")) {
            try { if(!p.get("from").matches("\\d{4}-(0[1-9]|1[0-2])"))throw invalid("from");from=YearMonth.parse(p.get("from")); }
            catch(DateTimeException e){throw invalid("from");}
            if(from.isBefore(next) || from.isAfter(next.plusMonths(5)))throw invalid("from");
        }
        int months=integer(p,"months",6,1,6),page=integer(p,"page",0,0,Integer.MAX_VALUE),size=integer(p,"size",50,20,100);
        if(!Set.of(20,50,100).contains(size))throw invalid("size");
        Integer year=p.containsKey("year")?integer(p,"year",1,1,9999):null;
        String sort=p.getOrDefault("sort","job_cd,asc");
        if(!sort.matches("(job_cd|grd_cd|maximum_shortage_mm|maximum_shortage_people|first_shortage_month|recommended_hiring_month|candidate_count),(asc|desc)"))throw invalid("sort");
        return new ShortageQuery(from,months,code(p,"job"),code(p,"grade"),year,sort,page,size);
    }
    private static String code(Map<String,String> p,String key) {
        if(!p.containsKey(key))return null;String v=p.get(key);
        if(!v.matches("[A-Z0-9_]{1,30}"))throw invalid(key);return v;
    }
    private static int integer(Map<String,String> p,String key,int fallback,int min,int max) {
        if(!p.containsKey(key))return fallback;
        try {int n=Integer.parseInt(p.get(key));if(n<min||n>max)throw invalid(key);return n;}
        catch(NumberFormatException e){throw invalid(key);}
    }
}
