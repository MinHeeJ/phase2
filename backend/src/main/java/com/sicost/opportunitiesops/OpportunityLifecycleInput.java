package com.sicost.opportunitiesops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.*;
import java.util.*;
import static com.sicost.opportunitiesops.OpportunityLifecycleModels.*;

public final class OpportunityLifecycleInput {
    private OpportunityLifecycleInput() {}
    public static ApiException invalid(String field) { return ApiException.badRequest("입력값을 확인하세요.",field); }
    public static long id(String value) {
        try { if (!value.matches("[0-9]{1,19}")) throw invalid("id"); long id=Long.parseLong(value); if(id<1)throw invalid("id"); return id; }
        catch(NumberFormatException e) { throw invalid("id"); }
    }
    public static void fields(JsonNode n, Set<String> allowed) {
        if(n==null || !n.isObject())throw invalid(null);
        n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid(k);});
    }
    public static String text(JsonNode n,String key,int maximum,boolean required) {
        var v=n.get(key); if(v==null){if(required)throw invalid(key);return null;}
        if(!v.isTextual())throw invalid(key);String s=v.textValue().trim();
        if(s.length()>maximum || required&&s.isEmpty())throw invalid(key);return s;
    }
    public static String stage(JsonNode n) {
        fields(n,Set.of("bsn_stg_cd"));String stage=text(n,"bsn_stg_cd",10,true);
        if(!Set.of("DISC","PROP","NEGO","PRE").contains(stage))throw invalid("bsn_stg_cd");return stage;
    }
    public static String reason(JsonNode n) { fields(n,Set.of("reason"));return text(n,"reason",500,true); }
    public static Approval approval(JsonNode n) {
        fields(n,Set.of("opinion","zero_cost_evidence"));
        return new Approval(text(n,"opinion",500,false),text(n,"zero_cost_evidence",65535,n.has("zero_cost_evidence")));
    }
    public static String month(String s,String field) {
        if(s==null||s.isBlank())return null;
        try { if(!s.matches("[0-9]{4}-(0[1-9]|1[0-2])")||YearMonth.parse(s).getYear()<1)throw invalid(field);return s; }
        catch(DateTimeException e){throw invalid(field);}
    }
    public static int integer(Map<String,String> p,String key,int fallback,int min,int max) {
        try {String v=p.get(key);if(v==null)return fallback;if(!v.matches("[0-9]+"))throw invalid(key);int i=Integer.parseInt(v);if(i<min||i>max)throw invalid(key);return i;}
        catch(NumberFormatException e){throw invalid(key);}
    }
    private static Long positive(Map<String,String> p,String key) {
        String s=p.get(key);if(s==null||s.isBlank())return null;
        try{long n=Long.parseLong(s);if(n<1)throw invalid(key);return n;}catch(NumberFormatException e){throw invalid(key);}
    }
    public static ExportQuery export(Map<String,String> p) {
        Set<String> allowed=Set.of("q","oppt_stts_cd","bsn_stg_cd","cust_ptnr_id","bsn_part_id","from","to");
        p.keySet().forEach(k->{if(!allowed.contains(k))throw invalid(k);});
        String status=p.getOrDefault("oppt_stts_cd","OPEN"),stage=p.get("bsn_stg_cd");
        if(!Set.of("OPEN","WON","LOST").contains(status))throw invalid("oppt_stts_cd");
        if(stage!=null&&!Set.of("DISC","PROP","NEGO","PRE").contains(stage))throw invalid("bsn_stg_cd");
        String from=month(p.get("from"),"from"),to=month(p.get("to"),"to");
        if(from!=null&&to!=null&&from.compareTo(to)>0)throw invalid("to");
        String q=p.get("q");if(q!=null){q=q.trim();if(q.length()>200)throw invalid("q");if(q.isEmpty())q=null;}
        return new ExportQuery(q,status,stage,positive(p,"cust_ptnr_id"),positive(p,"bsn_part_id"),from,to);
    }
}
