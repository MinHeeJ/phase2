package com.sicost.candidates;

import java.time.*;
import java.util.*;
import static com.sicost.candidates.CandidateInput.*;

public record CandidateQuery(String q,String job,String grade,String skill,String candType,Long vendor,String state,
    LocalDate available,boolean uncontacted,int page,int size,String order,LocalDate today) {
    public int offset(){return Math.multiplyExact(page,size);}
    public static CandidateQuery of(Map<String,String> p,LocalDate today) {
        var allowed=Set.of("q","job","grade","skill","cand_se_cd","vndr_ptnr_id","cntct_stts_cd","avail_dt","shortage_month","uncontacted_only","sort","page","size");
        for(var key:p.keySet())if(!allowed.contains(key))throw invalid(key);
        String type=value(p,"cand_se_cd"),state=value(p,"cntct_stts_cd");
        if(type!=null&&!Set.of("FL","OS").contains(type))throw invalid("cand_se_cd");
        if(state!=null&&!STATES.contains(state))throw invalid("cntct_stts_cd");
        for(var k:List.of("job","grade","skill")){String v=value(p,k);if(v!=null&&!v.matches("[A-Z0-9_]{1,30}"))throw invalid(k);}
        String term=value(p,"q");if(term!=null&&term.length()>200)throw invalid("q");
        int page=integer(p,"page",0),size=integer(p,"size",50);
        if(page<0||page>Integer.MAX_VALUE/100)throw invalid("page");
        if(!Set.of(20,50,100).contains(size))throw invalid("size");
        String sort=p.getOrDefault("sort","cand_id,asc");String[] parts=sort.split(",",-1);
        var columns=Map.of("cand_id","c.cand_id","cand_no","c.cand_no","cand_nm","c.cand_nm","avail_dt","c.asgn_psbl_dt","hope_untprc_amt","c.hope_untprc_amt","last_cntct_dt","c.last_cntct_dt");
        if(parts.length!=2||!columns.containsKey(parts[0])||!Set.of("asc","desc").contains(parts[1]))throw invalid("sort");
        String order=columns.get(parts[0])+" "+parts[1]+", c.cand_id ASC";
        LocalDate available=null;Long vendor=null;
        try {String v=value(p,"avail_dt");if(v!=null){if(!v.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid("avail_dt");available=LocalDate.parse(v);}}catch(RuntimeException e){throw invalid("avail_dt");}
        String month=value(p,"shortage_month");
        if(month!=null){try {if(!month.matches("[0-9]{4}-(0[1-9]|1[0-2])"))throw invalid("shortage_month");var end=YearMonth.parse(month).atEndOfMonth();if(available==null||end.isBefore(available))available=end;}catch(RuntimeException e){throw invalid("shortage_month");}}
        try {String v=value(p,"vndr_ptnr_id");if(v!=null){vendor=Long.valueOf(v);if(vendor<1)throw invalid("vndr_ptnr_id");}}catch(RuntimeException e){throw invalid("vndr_ptnr_id");}
        var uncontacted=p.getOrDefault("uncontacted_only","false");if(!Set.of("true","false").contains(uncontacted))throw invalid("uncontacted_only");
        return new CandidateQuery(term,value(p,"job"),value(p,"grade"),value(p,"skill"),type,vendor,state,available,Boolean.parseBoolean(uncontacted),page,size,order,today);
    }
    private static String value(Map<String,String> p,String k){String v=p.get(k);return v==null||v.isBlank()?null:v.trim();}
    private static int integer(Map<String,String> p,String k,int fallback){try{return p.containsKey(k)?Integer.parseInt(p.get(k)):fallback;}catch(RuntimeException e){throw invalid(k);}}
}
