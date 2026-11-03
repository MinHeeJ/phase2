package com.sicost.candidates;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.sicost.candidates.CandidateModels.*;

public final class CandidateInput {
    private CandidateInput() {}
    public static final Set<String> STATES=Set.of("NONE","DONE","INTV","HOLD","SIGNED");
    public static void object(JsonNode n,Set<String> allowed) {
        if(n==null||!n.isObject())throw ApiException.badRequest("입력값을 확인하세요.",null);
        n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw ApiException.badRequest("허용하지 않는 항목입니다.",k);});
    }
    public static String text(JsonNode n,String k,int max,boolean required) {
        var v=n.get(k);
        if(v==null||v.isNull()){if(required)throw invalid(k);return null;}
        if(!v.isTextual())throw invalid(k);
        var s=v.textValue().trim();
        if(s.length()>max||required&&s.isEmpty())throw invalid(k);
        return s.isEmpty()?null:s;
    }
    public static Long number(JsonNode n,String k,boolean required,long minimum) {
        var v=n.get(k);if(v==null||v.isNull()){if(required)throw invalid(k);return null;}
        if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<minimum)throw invalid(k);
        return v.longValue();
    }
    public static LocalDate date(JsonNode n,String k,boolean required) {
        String s=text(n,k,10,required);if(s==null)return null;
        try {if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid(k);return LocalDate.parse(s);}
        catch(DateTimeParseException e){throw invalid(k);}
    }
    public static ApiException invalid(String k){return ApiException.badRequest("입력값의 형식·범위를 확인하세요.",k);}
    public static long id(long id){if(id<1)throw invalid("id");return id;}
    public static CandidateSave parse(JsonNode n) {
        object(n,Set.of("cand_nm","cand_se_cd","vndr_ptnr_id","job_cd","grd_cd","skill","hope_untprc_amt","avail_dt","cntct_stts_cd","tel_no","source","rmrk_desc"));
        String type=text(n,"cand_se_cd",10,true);
        if(!Set.of("FL","OS").contains(type))throw invalid("cand_se_cd");
        Long partner=type.equals("OS")?number(n,"vndr_ptnr_id",true,1):null;
        String state=text(n,"cntct_stts_cd",10,false);if(state==null)state="NONE";
        if(!STATES.contains(state))throw invalid("cntct_stts_cd");
        var skills=new TreeSet<String>();var values=n.get("skill");
        if(values!=null){if(!values.isArray())throw invalid("skill");for(var v:values){if(!v.isTextual()||!v.textValue().matches("[A-Z0-9_]{1,30}"))throw invalid("skill");skills.add(v.textValue());}}
        return new CandidateSave(text(n,"cand_nm",50,true),type,partner,text(n,"job_cd",30,true),text(n,"grd_cd",30,false),
            List.copyOf(skills),number(n,"hope_untprc_amt",true,0),date(n,"avail_dt",true),state,text(n,"tel_no",30,false),text(n,"source",30,false),text(n,"rmrk_desc",10000,false));
    }
    public static ContactInput contact(JsonNode n,LocalDate today) {
        object(n,Set.of("cntct_dt","cntct_stts_cd","memo"));
        var date=date(n,"cntct_dt",true);if(date.isAfter(today))throw invalid("cntct_dt");
        var state=text(n,"cntct_stts_cd",10,true);if(!STATES.contains(state))throw invalid("cntct_stts_cd");
        return new ContactInput(date,state,text(n,"memo",1000,true));
    }
}
