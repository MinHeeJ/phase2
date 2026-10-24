package com.sicost.partners;

import com.sicost.common.api.*;
import java.time.LocalDate;
import java.util.Set;

public record PartnerQuery(int page,int size,long offset,String order,String kind,String useYn,String q,LocalDate today) {
    public static PartnerQuery of(PageRequest p,String kind,String useYn,String q,LocalDate today) {
        if(useYn==null) useYn="Y";
        if(kind!=null && !kind.isBlank() && !Set.of("CUST","VEND","BOTH").contains(kind)) throw ApiException.badRequest("구분을 확인하세요.","kind");
        if(useYn!=null && !useYn.isEmpty() && !Set.of("Y","N").contains(useYn)) throw ApiException.badRequest("사용 여부를 확인하세요.","useYn");
        String order="ptnr_nm ASC, ptnr_id ASC";
        if(p.sort()!=null && !p.sort().isBlank()) {
            String[] bits=p.sort().split(",",-1);
            Set<String> fields=Set.of("ptnr_id","ptnr_no","ptnr_se_cd","ptnr_nm","biz_reg_no","ceo_nm","indty_cd","chrg_nm","chrg_tel_no","txinv_email_addr","use_yn","kindName","industryName","paymentAccount","projectCount","assignmentPeopleCount");
            if(bits.length!=2 || !fields.contains(bits[0]) || !Set.of("asc","desc").contains(bits[1])) throw ApiException.badRequest("정렬을 확인하세요.","sort");
            order="\""+bits[0]+"\" "+bits[1]+", ptnr_id ASC";
        }
        return new PartnerQuery(p.page(),p.size(),p.offset(),order,kind,useYn,q==null?null:q.trim(),today);
    }
}
