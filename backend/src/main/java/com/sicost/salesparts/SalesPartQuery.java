package com.sicost.salesparts;

import com.sicost.common.api.ApiException;
import com.sicost.common.api.PageRequest;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

public record SalesPartQuery(int page,int size,long offset,String order,String month,LocalDate today) {
    public static SalesPartQuery of(PageRequest page,LocalDate today) {
        Map<String,String> columns=Map.of("bsn_part_nm","bsn_part_nm","ldr_nm","ldr_nm","prft_dstrb_rt","prft_dstrb_rt",
            "bsn_fee_amt","bsn_fee_amt","aply_bgn_ym","aply_bgn_ym","peopleCount","\"peopleCount\"","projectCount","\"projectCount\"","use_yn","use_yn");
        String order="sort_seq ASC, bsn_part_id ASC";
        if(page.sort()!=null && !page.sort().isBlank()) {
            String[] bits=page.sort().split(",",-1);
            if(bits.length!=2 || !columns.containsKey(bits[0]) || !Set.of("asc","desc").contains(bits[1])) throw ApiException.badRequest("정렬을 확인하세요.","sort");
            order=columns.get(bits[0])+" "+bits[1]+" NULLS LAST, bsn_part_id ASC";
        }
        return new SalesPartQuery(page.page(),page.size(),page.offset(),order,today.toString().substring(0,7),today);
    }
}
