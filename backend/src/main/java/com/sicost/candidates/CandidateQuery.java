package com.sicost.candidates;

import java.time.*;
import java.util.*;

public record CandidateQuery(String q, String job, String grade, String skill, String cand_se_cd,
        Long vndr_ptnr_id, String cntct_stts_cd, LocalDate avail_dt, LocalDate shortageEnd,
        boolean uncontacted_only, String order, int page, int size, long offset,
        LocalDate cutoff, LocalDate nextMonthEnd) {
    public static CandidateQuery of(Map<String,String> values, LocalDate today) {
        var allowed=Set.of("q","job","grade","skill","cand_se_cd","vndr_ptnr_id","cntct_stts_cd",
            "avail_dt","shortage_month","uncontacted_only","sort","page","size");
        values.keySet().forEach(k -> { if(!allowed.contains(k)) throw CandidateInput.invalid(k); });
        String type=CandidateInput.choice(value(values,"cand_se_cd"),Set.of("FL","OS"),"cand_se_cd");
        String status=CandidateInput.choice(value(values,"cntct_stts_cd"),CandidateInput.STATUSES,"cntct_stts_cd");
        Long vendor=null;
        try { if(value(values,"vndr_ptnr_id")!=null) { vendor=Long.valueOf(values.get("vndr_ptnr_id")); if(vendor<1) throw CandidateInput.invalid("vndr_ptnr_id"); } }
        catch(NumberFormatException e) { throw CandidateInput.invalid("vndr_ptnr_id"); }
        String uncontacted=value(values,"uncontacted_only");
        CandidateInput.choice(uncontacted,Set.of("true","false"),"uncontacted_only");
        int page=integer(values,"page",0),size=integer(values,"size",50);
        if(!Set.of(20,50,100).contains(size)) throw CandidateInput.invalid("size");
        String sort=values.getOrDefault("sort","cand_id,asc");
        String[] pair=sort.split(",",-1);
        Set<String> columns=Set.of("cand_id","cand_no","cand_nm","cand_se_cd","vndr_ptnr_id","job_cd","grd_cd",
            "hope_untprc_amt","avail_dt","cntct_stts_cd","last_cntct_dt","source","created_at");
        if(pair.length!=2 || !columns.contains(pair[0]) || !Set.of("asc","desc").contains(pair[1])) throw CandidateInput.invalid("sort");
        LocalDate shortage=null;
        String month=value(values,"shortage_month");
        if(month!=null) {
            if(!month.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw CandidateInput.invalid("shortage_month");
            shortage=YearMonth.parse(month).atEndOfMonth();
        }
        String available=value(values,"avail_dt");
        return new CandidateQuery(value(values,"q"),value(values,"job"),value(values,"grade"),value(values,"skill"),
            type,vendor,status,available==null?null:CandidateInput.date(available,"avail_dt"),shortage,
            "true".equals(uncontacted),"\""+pair[0]+"\" "+pair[1]+" NULLS LAST, cand_id ASC",page,size,
            (long)page*size,today.minusDays(30),YearMonth.from(today).plusMonths(1).atEndOfMonth());
    }
    private static String value(Map<String,String> v,String k) { String s=v.get(k); return s==null||s.isBlank()?null:s.trim(); }
    private static int integer(Map<String,String> v,String k,int fallback) {
        try { int n=v.containsKey(k)?Integer.parseInt(v.get(k)):fallback; if(n<0) throw CandidateInput.invalid(k); return n; }
        catch(NumberFormatException e) { throw CandidateInput.invalid(k); }
    }
}
