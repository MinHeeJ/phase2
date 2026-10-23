package com.sicost.partners;

import com.sicost.common.api.*;
import java.time.LocalDate;
import java.util.*;

public record PartnerQuery(String kind,String useYn,String q,int page,int size,long offset,String order,LocalDate today) {
    public static PartnerQuery of(Map<String,String> params, LocalDate today) {
        String kind=params.get("kind"),use=params.getOrDefault("useYn","Y"),q=params.get("q");
        if(kind!=null && !kind.isEmpty() && !Set.of("CUST","VEND","BOTH").contains(kind)) throw ApiException.badRequest("구분을 확인하세요.","kind");
        if(!Set.of("Y","N","").contains(use)) throw ApiException.badRequest("사용 여부를 확인하세요.","useYn");
        int page=integer(params,"page",1),size=integer(params,"size",50);
        PageRequest paging=new PageRequest(page,size,params.get("sort"));
        Map<String,String> keys=new HashMap<>();
        for(String key:List.of("ptnr_se_cd","ptnr_no","ptnr_nm","biz_reg_no","ceo_nm","indty_cd","chrg_nm","chrg_tel_no","txinv_email_addr","use_yn")) keys.put(key,"p."+key);
        for(String key:List.of("kindName","industryName","projectCount","assignmentPeopleCount")) keys.put(key,"\""+key+"\"");
        keys.put("paymentAccount","p.bank_cd");
        String sort=params.getOrDefault("sort","ptnr_nm,asc");String[] parts=sort.split(",",-1);
        if(parts.length!=2 || !keys.containsKey(parts[0]) || !Set.of("asc","desc").contains(parts[1])) throw ApiException.badRequest("정렬을 확인하세요.","sort");
        String order=keys.get(parts[0])+" "+parts[1];
        if(parts[0].equals("paymentAccount")) order+=",p.acnt_no "+parts[1];
        return new PartnerQuery(kind,use,q==null?null:q.trim(),page,size,paging.offset(),order+",p.ptnr_id ASC",today);
    }
    private static int integer(Map<String,String> params,String key,int fallback) {
        try {return Integer.parseInt(params.getOrDefault(key,Integer.toString(fallback)));}
        catch(NumberFormatException e){throw ApiException.badRequest("입력값을 확인하세요.",key);}
    }
}
