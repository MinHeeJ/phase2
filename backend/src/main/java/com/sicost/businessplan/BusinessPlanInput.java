package com.sicost.businessplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.util.*;
import static com.sicost.businessplan.BusinessPlanModels.*;

public final class BusinessPlanInput {
    private BusinessPlanInput() {}
    public static ApiException invalid(String field) {
        return new ApiException(400,"VALIDATION_ERROR","입력값을 확인하세요.",field);
    }
    public static int year(String value) {return integer(value,1,9999,"year");}
    public static int month(String value) {return integer(value,1,12,"month");}
    public static Long part(String value) {
        if(value==null)return null;
        if(!value.matches("[0-9]{1,19}"))throw invalid("partId");
        try {long id=Long.parseLong(value);if(id<1)throw invalid("partId");return id;}
        catch(NumberFormatException ex){throw invalid("partId");}
    }
    private static int integer(String value,int min,int max,String field) {
        if(value==null||!value.matches("[0-9]{1,4}"))throw invalid(field);
        int number=Integer.parseInt(value);
        if(number<min||number>max)throw invalid(field);return number;
    }
    public static List<Plan> parse(JsonNode body,int year) {
        if(body==null||!body.isObject()||!body.path("items").isArray())throw invalid("items");
        fields(body,Set.of("items"));
        var result=new ArrayList<Plan>();
        for(JsonNode item:body.get("items")) {
            if(!item.isObject())throw invalid("items");
            fields(item,Set.of("year","month","partId","target_revenue","target_operating_profit","target_utilization_rate"));
            long y=number(item,"year"),m=number(item,"month");
            if(y!=year)throw invalid("year");
            if(m<1||m>12)throw invalid("month");
            JsonNode part=item.get("partId");
            if(part==null)throw invalid("partId");
            Long id=number(item,"partId");
            if(id<1)throw invalid("partId");
            long revenue=number(item,"target_revenue"),profit=number(item,"target_operating_profit");
            if(revenue<0)throw invalid("target_revenue");
            JsonNode rate=item.get("target_utilization_rate");
            if(rate==null||!rate.isNumber())throw invalid("target_utilization_rate");
            BigDecimal utilization=rate.decimalValue();
            if(utilization.signum()<0||utilization.compareTo(BigDecimal.valueOf(100))>0||utilization.stripTrailingZeros().scale()>2)
                throw invalid("target_utilization_rate");
            result.add(new Plan(year,(int)m,id,revenue,profit,utilization));
        }
        var keys=new HashSet<String>();
        for(Plan p:result)if(!keys.add(p.month()+":"+p.partId()))throw invalid("items");
        return List.copyOf(result);
    }
    private static long number(JsonNode body,String field) {
        JsonNode n=body.get(field);
        if(n==null||!n.isIntegralNumber()||!n.canConvertToLong())throw invalid(field);
        return n.longValue();
    }
    private static void fields(JsonNode body,Set<String> allowed) {
        body.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid(k);});
    }
}
