package com.sicost.salespartsops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CalendarValues;
import java.math.BigDecimal;
import java.util.Set;
import static com.sicost.salespartsops.SalesPartModels.*;

final class SalesPartInput {
    private SalesPartInput() {}
    static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw ApiException.badRequest("파트 번호를 확인하세요.", "id");
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { throw ApiException.badRequest("파트 번호를 확인하세요.", "id"); }
    }
    static void object(JsonNode n, String... allowed) {
        if (n == null || !n.isObject()) throw ApiException.badRequest("입력값을 확인하세요.", null);
        Set<String> keys=Set.of(allowed);
        n.fieldNames().forEachRemaining(k->{if(!keys.contains(k)) throw ApiException.badRequest("변경할 수 없는 항목입니다.", k);});
    }
    static String text(JsonNode n, String key, int max, String message) {
        JsonNode v=n.get(key);
        if(v==null || !v.isTextual() || v.textValue().isBlank() || v.textValue().length()>max) throw ApiException.badRequest(message,key);
        return v.textValue();
    }
    static String name(JsonNode n) {
        object(n,"bsn_part_nm");
        return text(n,"bsn_part_nm",30,"파트명을 입력해 주세요.");
    }
    static StandardSave standard(JsonNode n) {
        object(n,"aply_bgn_ym","ldr_nm","prft_dstrb_rt","bsn_fee_amt");
        String month=text(n,"aply_bgn_ym",7,"적용 시작월을 선택해 주세요.");
        CalendarValues.month(month,"aply_bgn_ym");
        String leader=text(n,"ldr_nm",50,"파트장을 입력해 주세요.");
        JsonNode rate=n.get("prft_dstrb_rt");
        if(rate==null || !rate.isNumber()) throw rateError();
        BigDecimal r=rate.decimalValue();
        if(r.signum()<0 || r.compareTo(BigDecimal.valueOf(100))>0 || r.stripTrailingZeros().scale()>2) throw rateError();
        JsonNode fee=n.get("bsn_fee_amt");
        if(fee==null || !fee.isIntegralNumber() || !fee.canConvertToLong() || fee.longValue()<1) throw ApiException.badRequest("영업비를 입력해 주세요.","bsn_fee_amt");
        return new StandardSave(month,leader,r,fee.longValue());
    }
    private static ApiException rateError() { return ApiException.badRequest("이익 배분율은 0~100 사이 숫자로 입력해 주세요.","prft_dstrb_rt"); }
}
