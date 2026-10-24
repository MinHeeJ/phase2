package com.sicost.salesparts;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CalendarValues;
import java.math.BigDecimal;
import java.util.Set;
import static com.sicost.salesparts.SalesPartModels.*;

final class SalesPartInput {
    private SalesPartInput() {}
    static SalesPartCreate create(JsonNode body) {
        if(body==null || !body.isObject()) throw ApiException.badRequest("입력값을 확인하세요.",null);
        Set<String> allowed=Set.of("bsn_part_nm","aply_bgn_ym","ldr_nm","prft_dstrb_rt","bsn_fee_amt");
        body.fieldNames().forEachRemaining(key->{if(!allowed.contains(key)) throw ApiException.badRequest("변경할 수 없는 항목입니다.",key);});
        String name=text(body,"bsn_part_nm",30,"파트명을 입력해 주세요.");
        String month=text(body,"aply_bgn_ym",7,"올바른 연월을 입력하세요.");
        CalendarValues.month(month,"aply_bgn_ym");
        String leader=text(body,"ldr_nm",50,"파트장을 입력해 주세요.");
        JsonNode rate=body.get("prft_dstrb_rt"),fee=body.get("bsn_fee_amt");
        if(rate==null || !rate.isNumber()) throw ApiException.badRequest("이익 배분율은 0~100 사이 숫자로 입력해 주세요.","prft_dstrb_rt");
        BigDecimal value=rate.decimalValue();
        if(value.signum()<0 || value.compareTo(BigDecimal.valueOf(100))>0 || value.stripTrailingZeros().scale()>2) throw ApiException.badRequest("이익 배분율은 0~100 사이 숫자로 입력해 주세요.","prft_dstrb_rt");
        if(fee==null || !fee.isIntegralNumber() || !fee.canConvertToLong() || fee.longValue()<1) throw ApiException.badRequest("영업비를 입력해 주세요.","bsn_fee_amt");
        return new SalesPartCreate(name,month,leader,value,fee.longValue());
    }
    private static String text(JsonNode body,String key,int max,String message) {
        JsonNode value=body.get(key);
        if(value==null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length()>max) throw ApiException.badRequest(message,key);
        return value.textValue().trim();
    }
}
