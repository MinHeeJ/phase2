package com.sicost.tax;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.*;
import java.util.*;
import static com.sicost.tax.CorporateTaxModels.*;

public final class CorporateTaxInput {
    private CorporateTaxInput() {}
    public static ApiException invalid(String field) {
        return new ApiException(400,"VALIDATION_ERROR","입력값을 확인하세요.",field);
    }
    public static int year(String value) {
        if(value==null || !value.matches("[0-9]{1,4}")) throw invalid("year");
        int year=Integer.parseInt(value);
        if(year<1) throw invalid("year");
        return year;
    }
    public static String yearKey(int year) {
        if(year<1 || year>9999) throw invalid("year");
        return String.format(Locale.ROOT,"%04d",year);
    }
    private static void object(JsonNode body, Set<String> fields) {
        if(body==null || !body.isObject()) throw invalid(null);
        body.fieldNames().forEachRemaining(key->{if(!fields.contains(key)) throw invalid(key);});
    }
    public static PlanInput plan(JsonNode body,int year) {
        object(body,Set.of("year","annual_amount","reason"));
        if(body.has("year") && (!body.path("year").isIntegralNumber() || !body.path("year").canConvertToInt() || body.path("year").intValue()!=year)) throw invalid("year");
        return new PlanInput(money(body,"annual_amount",true),text(body,"reason",500,true));
    }
    public static List<PaymentInput> payments(JsonNode body) {
        object(body,Set.of("payments"));
        if(!body.path("payments").isArray() || body.path("payments").size()>2) throw invalid("payments");
        var result=new ArrayList<PaymentInput>();var types=new HashSet<String>();
        for(var row:body.get("payments")) {
            object(row,Set.of("tax_pay_se_cd","planned_date","planned_amount","actual_date","actual_amount","updated_at"));
            String type=text(row,"tax_pay_se_cd",10,true);
            if(!Set.of("FINAL","INTERIM").contains(type) || !types.add(type)) throw invalid("tax_pay_se_cd");
            LocalDate planned=date(row,"planned_date",true),actual=date(row,"actual_date",false);
            Long amount=money(row,"actual_amount",false);
            if((actual==null)!=(amount==null)) throw invalid(actual==null?"actual_date":"actual_amount");
            LocalDateTime version=null;
            String stamp=text(row,"updated_at",40,false);
            try {if(stamp!=null) version=LocalDateTime.parse(stamp);}
            catch(java.time.format.DateTimeParseException ex){throw invalid("updated_at");}
            result.add(new PaymentInput(type,planned,money(row,"planned_amount",true),actual,amount,version));
        }
        return List.copyOf(result);
    }
    private static String text(JsonNode body,String field,int max,boolean required) {
        var value=body.get(field);
        if(value==null || value.isNull()) {if(required) throw invalid(field);return null;}
        if(!value.isTextual() || value.textValue().length()>max || value.textValue().isBlank()) throw invalid(field);
        return value.textValue();
    }
    private static Long money(JsonNode body,String field,boolean required) {
        var value=body.get(field);
        if(value==null || value.isNull()) {if(required) throw invalid(field);return null;}
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<0) throw invalid(field);
        return value.longValue();
    }
    private static LocalDate date(JsonNode body,String field,boolean required) {
        String text=text(body,field,10,required);
        if(text==null) return null;
        try {if(!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid(field);return LocalDate.parse(text);}
        catch(java.time.format.DateTimeParseException ex){throw invalid(field);}
    }
}
