package com.sicost.salesparts;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CalendarValues;
import java.math.BigDecimal;
import java.util.Set;
import static com.sicost.salesparts.SalesPartModels.*;

final class SalesPartInput {
    private SalesPartInput() {}
    static SalesPartCreate create(JsonNode n) {
        if(n==null || !n.isObject()) throw ApiException.badRequest("입력값을 확인하세요.",null);
        var allowed=Set.of("bsn_part_nm","aply_bgn_ym","ldr_nm","prft_dstrb_rt","bsn_fee_amt");
        n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k)) throw ApiException.badRequest("변경할 수 없는 항목입니다.",k);});
        String name=text(n,"bsn_part_nm",30,"파트명을 입력해 주세요.");
        String month=text(n,"aply_bgn_ym",7,"올바른 연월을 입력하세요.");
        CalendarValues.month(month,"aply_bgn_ym");
        String leader=text(n,"ldr_nm",50,"파트장을 입력해 주세요.");
        JsonNode rate=n.get("prft_dstrb_rt"), fee=n.get("bsn_fee_amt");
        if(rate==null || !rate.isNumber()) throw rateError();
        BigDecimal number=rate.decimalValue();
        if(number.signum()<0 || number.compareTo(BigDecimal.valueOf(100))>0 || number.stripTrailingZeros().scale()>2) throw rateError();
        if(fee==null || !fee.isIntegralNumber() || !fee.canConvertToLong() || fee.longValue()<1) throw ApiException.badRequest("영업비를 입력해 주세요.","bsn_fee_amt");
        return new SalesPartCreate(name,month,leader,number,fee.longValue());
    }
    private static String text(JsonNode n,String key,int max,String message) {
        JsonNode v=n.get(key);
        if(v==null || !v.isTextual() || v.textValue().isBlank() || v.textValue().length()>max) throw ApiException.badRequest(message,key);
        return v.textValue().trim();
    }
    private static ApiException rateError() { return ApiException.badRequest("이익 배분율은 0~100 사이 숫자로 입력해 주세요.","prft_dstrb_rt"); }
    static Long id(String id) {
        try { if(!id.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.valueOf(id); }
        catch(NumberFormatException ex) { throw ApiException.badRequest("파트 번호를 확인하세요.","id"); }
    }
}
