package com.sicost.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.ai.AiDataConventions;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.sicost.analysis.AnalysisModels.*;

public final class AnalysisInput {
    private AnalysisInput() {}
    static ApiException invalid(String field) { return ApiException.badRequest("입력 조건을 확인하세요.",field); }
    static void object(JsonNode n, String field, Set<String> keys) {
        if(n==null || !n.isObject())throw invalid(field);
        n.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid(k);});
    }
    static String text(JsonNode n,String key,boolean required) {
        var v=n.get(key); if(v==null) {if(required)throw invalid(key);return null;}
        if(!v.isTextual() || required&&v.textValue().isBlank())throw invalid(key);return v.textValue();
    }
    static String choice(JsonNode n,String key,Set<String> choices,String fallback) {
        var v=text(n,key,false);if(v==null)return fallback;if(!choices.contains(v))throw invalid(key);return v;
    }
    static int integer(JsonNode n,String key,int min,int max,int fallback) {
        var v=n.get(key);if(v==null)return fallback;
        if(!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue()<min || v.intValue()>max)throw invalid(key);
        return v.intValue();
    }
    static BigDecimal decimal(JsonNode n,String key,BigDecimal min,BigDecimal max,int scale,boolean required) {
        var v=n.get(key);if(v==null){if(required)throw invalid(key);return null;}
        if(!v.isNumber())throw invalid(key);var d=v.decimalValue();
        if(d.compareTo(min)<0 || max!=null&&d.compareTo(max)>0 || d.stripTrailingZeros().scale()>scale)throw invalid(key);return d;
    }
    static String month(JsonNode n,String key) {
        String value=text(n,key,false);return value==null?null:AiDataConventions.month(value,key).toString();
    }
    public static long id(String value,String field) {
        try {if(value==null||!value.matches("[1-9][0-9]*"))throw invalid(field);return Long.parseLong(value);}
        catch(NumberFormatException e){throw invalid(field);}
    }
    static Long optionalId(JsonNode n,String key) {
        var v=n.get(key);if(v==null)return null;if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<1)throw invalid(key);return v.longValue();
    }
    static <T> List<T> list(JsonNode n,String key,java.util.function.Function<JsonNode,T> convert) {
        var v=n.get(key);if(v==null)return new ArrayList<>();if(!v.isArray()||v.size()>20)throw invalid(key);
        var result=new ArrayList<T>();v.forEach(x->{T item=convert.apply(x);if(result.contains(item))throw invalid(key);result.add(item);});return List.copyOf(result);
    }
    public static Request structured(JsonNode n,Clock clock) {
        object(n,"query",Set.of("intent","baseYm","fromYm","toYm","periodType","sort","limit","filters","workforceAssumptions","virtualCostAssumption","needsClarification"));
        Intent intent;try {intent=Intent.valueOf(text(n,"intent",true));}catch(IllegalArgumentException e){throw invalid("intent");}
        String base=month(n,"baseYm"),from=month(n,"fromYm"),to=month(n,"toYm");
        if(base==null)base=AiDataConventions.currentMonth(clock).toString();
        if((from==null)!=(to==null))throw invalid("fromYm");
        if(from!=null){long months=ChronoUnit.MONTHS.between(YearMonth.parse(from),YearMonth.parse(to))+1;
            if(months<1||months>36||(intent==Intent.PROJECT_PROFIT_TREND||intent==Intent.MONTHLY_FINANCE)&&months>12)throw invalid("toYm");}
        Filters filters=filters(n.get("filters"));
        var assumptions=new ArrayList<WorkforceAssumption>();var a=n.get("workforceAssumptions");
        if(a!=null){if(!a.isArray()||a.size()>3)throw invalid("workforceAssumptions");for(var x:a){
            object(x,"workforceAssumptions",Set.of("type","opportunityId","assignmentId","probability"));
            String type=choice(x,"type",Set.of("OPPORTUNITY_WIN","EXTENSION"),null);if(type==null)throw invalid("type");
            Long opportunity=optionalId(x,"opportunityId"),assignment=optionalId(x,"assignmentId");
            if("OPPORTUNITY_WIN".equals(type)?opportunity==null||assignment!=null:assignment==null||opportunity!=null)throw invalid("workforceAssumptions");
            assumptions.add(new WorkforceAssumption(type,opportunity,assignment,decimal(x,"probability",BigDecimal.ZERO,new BigDecimal("100"),2,true)));
        }}
        if(!assumptions.isEmpty()&&!Set.of(Intent.WHAT_IF_WORKFORCE,Intent.FORECAST_STAFF).contains(intent))throw invalid("workforceAssumptions");
        VirtualCost virtual=null;var v=n.get("virtualCostAssumption");
        if(v!=null){if(intent!=Intent.WHAT_IF_PROJECT_COST)throw invalid("virtualCostAssumption");
            object(v,"virtualCostAssumption",Set.of("revenue","cost","costChangePercent","requestedMm","durationMonths"));
            virtual=new VirtualCost(decimal(v,"revenue",BigDecimal.ZERO,null,0,true),decimal(v,"cost",BigDecimal.ZERO,null,0,true),
                decimal(v,"costChangePercent",new BigDecimal("-50"),new BigDecimal("100"),2,true),
                decimal(v,"requestedMm",new BigDecimal("0.01"),BigDecimal.ONE,2,false),v.has("durationMonths")?integer(v,"durationMonths",1,36,1):null);
        }
        Boolean clarify=null;if(n.has("needsClarification")){if(!n.get("needsClarification").isBoolean())throw invalid("needsClarification");clarify=n.get("needsClarification").booleanValue();}
        return new Request(new Query(intent,base,from,to,choice(n,"periodType",Set.of("MONTH","YTD"),"MONTH"),
            choice(n,"sort",Set.of("PROFIT_ASC","PROFIT_DESC","MARGIN_ASC","MARGIN_DESC"),"PROFIT_DESC"),integer(n,"limit",1,20,5),filters,List.copyOf(assumptions),clarify),virtual);
    }
    static Filters filters(JsonNode n) {
        if(n==null)return new Filters(new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>());
        object(n,"filters",Set.of("projectIds","customerIds","partIds","devIds","jobCodes","gradeCodes","skillCodes"));
        java.util.function.Function<JsonNode,Long> ids=v->{if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<1)throw invalid("filters");return v.longValue();};
        java.util.function.Function<JsonNode,String> codes=v->{if(!v.isTextual()||!v.textValue().matches("[A-Za-z0-9_가-힣-]{1,40}"))throw invalid("filters");return v.textValue();};
        var f=new Filters(list(n,"projectIds",ids),list(n,"customerIds",ids),list(n,"partIds",ids),list(n,"devIds",ids),list(n,"jobCodes",codes),list(n,"gradeCodes",codes),list(n,"skillCodes",codes));
        if(f.projectIds().size()+f.customerIds().size()+f.partIds().size()+f.devIds().size()>20)throw invalid("filters");return f;
    }
    public static MessageInput message(JsonNode n) {
        object(n,"message",Set.of("question","baseYm","contextMessageId"));String q=text(n,"question",true);
        if(q.length()>1000)throw invalid("question");return new MessageInput(q,month(n,"baseYm"),optionalId(n,"contextMessageId"));
    }
    public static FeedbackInput feedback(JsonNode n) {
        object(n,"feedback",Set.of("rating","reason"));String rating=choice(n,"rating",Set.of("HELPFUL","INACCURATE"),null);
        if(rating==null)throw invalid("rating");String reason=text(n,"reason",false);if(reason!=null&&reason.length()>500)throw invalid("reason");return new FeedbackInput(rating,reason);
    }
    public static int page(Map<String,String> input) { return parameter(input,"page",0); }
    public static int size(Map<String,String> input) {int size=parameter(input,"size",50);if(!Set.of(20,50,100).contains(size))throw invalid("size");return size;}
    private static int parameter(Map<String,String> input,String key,int fallback) {
        try {String s=input.get(key);if(s==null)return fallback;if(!s.matches("[0-9]+"))throw invalid(key);return Integer.parseInt(s);}catch(NumberFormatException e){throw invalid(key);}
    }
}
