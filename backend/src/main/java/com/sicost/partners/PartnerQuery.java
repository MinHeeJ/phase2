package com.sicost.partners;

import com.sicost.common.api.*;
import java.time.LocalDate;
import java.util.*;

public record PartnerQuery(int page, int size, long offset, String order, String kind, String useYn, String q, LocalDate today) {
    private static final Map<String,String> SORTS=Map.ofEntries(
        Map.entry("ptnr_se_cd","\"kindName\""),Map.entry("ptnr_no","ptnr_no"),Map.entry("ptnr_nm","ptnr_nm"),
        Map.entry("biz_reg_no","biz_reg_no"),Map.entry("ceo_nm","ceo_nm"),Map.entry("indty_cd","\"industryName\""),
        Map.entry("chrg_nm","chrg_nm"),Map.entry("chrg_tel_no","chrg_tel_no"),Map.entry("txinv_email_addr","txinv_email_addr"),
        Map.entry("paymentAccount","\"paymentAccount\""),Map.entry("projectCount","\"projectCount\""),
        Map.entry("assignmentPeopleCount","\"assignmentPeopleCount\""),Map.entry("use_yn","use_yn"));
    public static PartnerQuery of(Map<String,String> input, LocalDate today) {
        PageRequest page=new PageRequest(integer(input,"page",1),integer(input,"size",50),input.get("sort"));
        String kind=input.get("kind"),use=input.getOrDefault("useYn","Y"),q=input.get("q");
        if(kind!=null && !kind.isEmpty() && !Set.of("CUST","VEND","BOTH").contains(kind)) throw ApiException.badRequest("구분을 확인하세요.","kind");
        if(use!=null && !use.isEmpty() && !Set.of("Y","N").contains(use)) throw ApiException.badRequest("사용 여부를 확인하세요.","useYn");
        String order="ptnr_nm ASC NULLS LAST, ptnr_id ASC";
        if(page.sort()!=null && !page.sort().isBlank()) {
            String[] bits=page.sort().split(",",-1);
            if(bits.length!=2 || !SORTS.containsKey(bits[0]) || !Set.of("asc","desc").contains(bits[1])) throw ApiException.badRequest("정렬을 확인하세요.","sort");
            order=SORTS.get(bits[0])+" "+bits[1].toUpperCase(Locale.ROOT)+" NULLS LAST, ptnr_id ASC";
        }
        return new PartnerQuery(page.page(),page.size(),page.offset(),order,kind,use,q==null?null:q.trim(),today);
    }
    private static int integer(Map<String,String> input,String field,int fallback) {
        try {return input.containsKey(field)?Integer.parseInt(input.get(field)):fallback;}
        catch(NumberFormatException e) {throw ApiException.badRequest("입력값을 확인하세요.",field);}
    }
}
