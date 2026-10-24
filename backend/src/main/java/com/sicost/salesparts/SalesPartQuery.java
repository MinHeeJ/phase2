package com.sicost.salesparts;

import com.sicost.common.api.ApiException;
import com.sicost.common.api.PageRequest;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

public record SalesPartQuery(int page, int size, long offset, String order, String month, LocalDate today) {
    public static SalesPartQuery of(PageRequest page,String month,LocalDate today) {
        Map<String,String> fields=Map.ofEntries(
            Map.entry("bsn_part_id","bsn_part_id"),Map.entry("bsn_part_nm","bsn_part_nm"),
            Map.entry("hq_yn","hq_yn"),Map.entry("use_yn","use_yn"),Map.entry("sort_seq","sort_seq"),
            Map.entry("referenceMonth","\"referenceMonth\""),
            Map.entry("peopleCount","\"peopleCount\""),Map.entry("projectCount","\"projectCount\""),
            Map.entry("currentStandard.ldr_nm","ldr_nm"),Map.entry("currentStandard.prft_dstrb_rt","prft_dstrb_rt"),
            Map.entry("currentStandard.bsn_fee_amt","bsn_fee_amt"),Map.entry("currentStandard.aply_bgn_ym","aply_bgn_ym"));
        String order="sort_seq ASC, bsn_part_id ASC";
        if(page.sort()!=null && !page.sort().isBlank()) {
            String[] bits=page.sort().split(",",-1);
            if(bits.length!=2 || !fields.containsKey(bits[0]) || !Set.of("asc","desc").contains(bits[1])) throw ApiException.badRequest("정렬을 확인하세요.","sort");
            order=fields.get(bits[0])+" "+bits[1]+", bsn_part_id ASC";
        }
        return new SalesPartQuery(page.page(),page.size(),page.offset(),order,month,today);
    }
}
