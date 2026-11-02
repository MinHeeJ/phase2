package com.sicost.cashflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.*;
import java.util.Set;
import static com.sicost.cashflow.CashFlowModels.*;

public final class CashFlowInput {
    private CashFlowInput() {}
    public static ApiException invalid(String field) {return new ApiException(400,"VALIDATION_ERROR","입력값을 확인하세요.",field);}
    public static YearMonth month(String value) {
        if(value==null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])") || value.startsWith("0000")) throw invalid("month");
        return YearMonth.parse(value);
    }
    public static int year(String value) {
        try {int year=Integer.parseInt(value);if(year<1||year>9999)throw invalid("year");return year;}
        catch(NumberFormatException ex){throw invalid("year");}
    }
    public static void object(JsonNode body, Set<String> keys) {
        if(body==null||!body.isObject())throw invalid(null);
        body.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid(k);});
    }
    public static String text(JsonNode body,String field,int max,boolean required) {
        var v=body.get(field);if(v==null||v.isNull()){if(required)throw invalid(field);return null;}
        if(!v.isTextual()||v.textValue().length()>max||(required&&v.textValue().isBlank()))throw invalid(field);
        return v.textValue();
    }
    public static long amount(JsonNode body,String field) {
        var v=body.get(field);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0)throw invalid(field);
        return v.longValue();
    }
    public static Opening opening(String query,JsonNode body) {
        month(query);object(body,Set.of("month","amount"));
        String echoed=text(body,"month",7,false);if(echoed!=null&&!query.equals(echoed))throw invalid("month");
        return new Opening(query,amount(body,"amount"));
    }
    private static LocalDate date(JsonNode body,String field) {
        String s=text(body,field,10,false);if(s==null)return null;
        try {if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid(field);return LocalDate.parse(s);}
        catch(DateTimeException e){throw invalid(field);}
    }
    public static Inflow inflow(JsonNode body,boolean update) {
        object(body,Set.of("inflw_id","month","amount","inflw_se_cd","inflw_item_nm","planned_date","actual_date","remark"));
        Long id=null;if(body.hasNonNull("inflw_id")){id=amount(body,"inflw_id");if(id==0)throw invalid("inflw_id");}
        if(update&&id==null)throw invalid("inflw_id");
        String m=text(body,"month",7,true);month(m);
        String code=text(body,"inflw_se_cd",10,true);if(!Set.of("MISC","LOAN","ETC").contains(code))throw invalid("inflw_se_cd");
        LocalDate plan=date(body,"planned_date"),actual=date(body,"actual_date");
        if(plan==null&&actual==null)throw invalid("planned_date");
        if(!(plan!=null&&YearMonth.from(plan).toString().equals(m))&&!(actual!=null&&YearMonth.from(actual).toString().equals(m)))throw invalid("month");
        return new Inflow(id,m,amount(body,"amount"),code,text(body,"inflw_item_nm",100,true),plan,actual,text(body,"remark",500,false));
    }
}
