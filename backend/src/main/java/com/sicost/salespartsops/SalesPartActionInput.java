package com.sicost.salespartsops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CalendarValues;
import java.math.BigDecimal;
import java.util.Set;
import static com.sicost.salespartsops.SalesPartActionModels.*;

final class SalesPartActionInput {
    private SalesPartActionInput() {}
    static void object(JsonNode input, Set<String> keys) {
        if(input==null || !input.isObject()) throw ApiException.badRequest("입력값을 확인하세요.",null);
        input.fieldNames().forEachRemaining(k->{if(!keys.contains(k)) throw ApiException.badRequest("변경할 수 없는 항목입니다.",k);});
    }
    static String text(JsonNode input,String key,int max,String message) {
        JsonNode value=input.get(key);
        if(value==null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length()>max) throw ApiException.badRequest(message,key);
        return value.textValue();
    }
    static String name(JsonNode input) {
        object(input,Set.of("bsn_part_nm"));
        return text(input,"bsn_part_nm",30,"파트명을 입력해 주세요.");
    }
    static StandardSave standard(JsonNode input) {
        object(input,Set.of("aply_bgn_ym","ldr_nm","prft_dstrb_rt","bsn_fee_amt"));
        String month=text(input,"aply_bgn_ym",7,"적용 시작월을 입력해 주세요.");CalendarValues.month(month,"aply_bgn_ym");
        String leader=text(input,"ldr_nm",50,"파트장을 입력해 주세요.");
        JsonNode rate=input.get("prft_dstrb_rt"),fee=input.get("bsn_fee_amt");
        if(rate==null || !rate.isNumber()) throw ApiException.badRequest("이익 배분율은 0~100 사이 숫자로 입력해 주세요.","prft_dstrb_rt");
        BigDecimal number=rate.decimalValue();
        if(number.signum()<0 || number.compareTo(BigDecimal.valueOf(100))>0 || number.stripTrailingZeros().scale()>2) throw ApiException.badRequest("이익 배분율은 0~100 사이 숫자로 입력해 주세요.","prft_dstrb_rt");
        if(fee==null || !fee.isIntegralNumber() || !fee.canConvertToLong() || fee.longValue()<1) throw ApiException.badRequest("영업비를 입력해 주세요.","bsn_fee_amt");
        return new StandardSave(month,leader,number,fee.longValue());
    }
}
