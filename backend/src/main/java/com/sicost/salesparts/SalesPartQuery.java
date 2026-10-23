package com.sicost.salesparts;

import com.sicost.common.api.*;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

public record SalesPartQuery(int page,int size,long offset,String order,String month,LocalDate today) {
    public static SalesPartQuery of(PageRequest p,LocalDate today) {
        Map<String,String> fields=Map.ofEntries(
            Map.entry("bsn_part_nm","bsn_part_nm"), Map.entry("sort_seq","sort_seq"), Map.entry("use_yn","use_yn"),
            Map.entry("peopleCount","\"peopleCount\""), Map.entry("projectCount","\"projectCount\""),
            Map.entry("currentStandard.ldr_nm","ldr_nm"), Map.entry("currentStandard.prft_dstrb_rt","prft_dstrb_rt"),
            Map.entry("currentStandard.bsn_fee_amt","bsn_fee_amt"), Map.entry("currentStandard.aply_bgn_ym","aply_bgn_ym"));
        String order="sort_seq ASC, bsn_part_id ASC";
        if(p.sort()!=null && !p.sort().isBlank()) {
            String[] bits=p.sort().split(",",-1);
            if(bits.length!=2 || !fields.containsKey(bits[0]) || !Set.of("asc","desc").contains(bits[1])) throw ApiException.badRequest("정렬을 확인하세요.","sort");
            order=fields.get(bits[0])+" "+bits[1]+", bsn_part_id ASC";
        }
        return new SalesPartQuery(p.page(),p.size(),p.offset(),order,today.toString().substring(0,7),today);
    }
}
