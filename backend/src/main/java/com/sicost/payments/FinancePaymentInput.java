package com.sicost.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.*;
import java.util.Set;
import static com.sicost.payments.FinancePaymentModels.*;

public final class FinancePaymentInput {
    private FinancePaymentInput() {}
    public static ApiException invalid(String field) {
        return new ApiException(400,"VALIDATION_ERROR","입력값을 확인하세요.",field);
    }
    public static String month(String value) {
        try {
            if(value==null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])") || YearMonth.parse(value).getYear()<1)
                throw invalid("month");
            return value;
        } catch(DateTimeException ex) { throw invalid("month"); }
    }
    public static JsonNode object(JsonNode body) {
        if(body==null || !body.isObject()) throw invalid(null);
        return body;
    }
    public static PaymentInput parse(JsonNode b) {
        object(b);
        var allowed=Set.of("pay_id","pay_ym","pay_odr_no","pay_se_cd","rcvr_se_cd","dev_id","ptnr_id","recipient",
            "sply_amt","vat_amt","ddct_amt","pay_amt","pay_plan_dt","bank","account","dpstr_nm","remark","updated_at");
        b.fieldNames().forEachRemaining(f->{if(!allowed.contains(f)) throw invalid(f);});
        String m=text(b,"pay_ym",7,true);
        try { month(m); } catch(ApiException ex) { throw invalid("pay_ym"); }
        Long order=optionalNumber(b,"pay_odr_no");
        if(order!=null && (order<1 || order>5)) throw invalid("pay_odr_no");
        String receiver=text(b,"rcvr_se_cd",10,true);
        if(!Set.of("DEV","PARTNER","AGENCY","ETC").contains(receiver)) throw invalid("rcvr_se_cd");
        var result=new PaymentInput(m,order==null?null:order.intValue(),text(b,"pay_se_cd",20,true),receiver,
            optionalNumber(b,"dev_id"),optionalNumber(b,"ptnr_id"),text(b,"recipient",100,true),
            number(b,"sply_amt"),number(b,"vat_amt"),number(b,"ddct_amt"),date(b,"pay_plan_dt"),
            text(b,"bank",30,true),text(b,"account",30,true),text(b,"dpstr_nm",50,true),text(b,"remark",500,false),version(b));
        if(!YearMonth.from(result.pay_plan_dt()).toString().equals(m)) throw invalid("pay_plan_dt");
        try { if(result.amount()<0) throw invalid("pay_amt"); } catch(ArithmeticException ex) { throw invalid("pay_amt"); }
        if(!result.account().matches("[0-9 -]{1,30}")) throw invalid("account");
        return result;
    }
    public static String text(JsonNode b,String field,int max,boolean required) {
        JsonNode n=b.get(field);
        if(n==null || n.isNull()) { if(required) throw invalid(field); return null; }
        if(!n.isTextual() || n.textValue().length()>max || (required && n.textValue().isBlank())) throw invalid(field);
        return n.textValue().trim();
    }
    public static long number(JsonNode b,String field) {
        JsonNode n=b.get(field);
        if(n==null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue()<0) throw invalid(field);
        return n.longValue();
    }
    public static Long optionalNumber(JsonNode b,String f) { return !b.hasNonNull(f)?null:number(b,f); }
    public static LocalDate date(JsonNode b,String field) {
        try { return LocalDate.parse(text(b,field,10,true)); } catch(DateTimeException ex) { throw invalid(field); }
    }
    public static LocalDateTime version(JsonNode b) {
        String v=text(b,"updated_at",40,false);
        try { return v==null?null:LocalDateTime.parse(v); } catch(DateTimeException ex) { throw invalid("updated_at"); }
    }
    public static CompleteInput complete(JsonNode b) {
        object(b); Long amount=optionalNumber(b,"paid_amount");
        if(amount!=null && amount<1) throw invalid("paid_amount");
        if(amount!=null && !b.hasNonNull("updated_at")) throw invalid("updated_at");
        return new CompleteInput(date(b,"pay_dt"),amount,version(b));
    }
}
