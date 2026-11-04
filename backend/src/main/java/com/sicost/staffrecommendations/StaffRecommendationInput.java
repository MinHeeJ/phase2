package com.sicost.staffrecommendations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sicost.common.ai.AiDataConventions;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.util.*;
import static com.sicost.staffrecommendations.StaffRecommendationModels.*;

public final class StaffRecommendationInput {
    private StaffRecommendationInput() {}
    public static void keys(JsonNode node, Set<String> allowed) {
        if(node==null || !node.isObject()) throw invalid("body");
        node.fieldNames().forEachRemaining(k -> { if(!allowed.contains(k)) throw invalid(k); });
    }
    public static long id(String value, String field) {
        try { long n=Long.parseLong(value); if(n>0)return n; } catch(NumberFormatException ignored) { }
        throw invalid(field);
    }
    public static Long identifier(JsonNode node,String field) {
        if(!node.has(field))return null;
        JsonNode value=node.get(field);
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<1)throw invalid(field);
        return value.longValue();
    }
    public static String direction(String value) {
        if(!A.equals(value) && !B.equals(value))throw invalid("direction");return value;
    }
    public static Create create(ObjectNode node) {
        String direction=direction(node.path("direction").asText());
        keys(node,A.equals(direction)?Set.of("direction","sourceId","criteria"):Set.of("direction","devId","criteria"));
        Long source=identifier(node,"sourceId"),dev=identifier(node,"devId");
        if(B.equals(direction)&&dev==null)throw invalid("devId");
        ObjectNode criteria=node.has("criteria") ? object(node.get("criteria")) : JsonNodeFactory.instance.objectNode();
        Set<String> allowed=new HashSet<>(Set.of("jobCode","gradeCode","skillCodes","customerId","industryCode","requestedMm","assumedExitYm"));
        if(A.equals(direction))allowed.addAll(Set.of("startYm","durationMonths","headcount"));
        keys(criteria,allowed); validateCriteria(criteria);
        return new Create(direction,source,dev,criteria.deepCopy());
    }
    public static ObjectNode object(JsonNode node) { if(node==null || !node.isObject())throw invalid("criteria");return (ObjectNode)node; }
    public static void validateCriteria(ObjectNode c) {
        for(String key:List.of("startYm","assumedExitYm"))if(c.has(key))AiDataConventions.month(c.path(key).asText(),key);
        for(String key:List.of("jobCode","gradeCode","industryCode"))if(c.has(key)&&(!c.get(key).isTextual()||c.get(key).asText().isBlank()||c.get(key).asText().length()>30))throw invalid(key);
        for(String key:List.of("durationMonths","headcount"))if(c.has(key)) {
            int max=key.equals("headcount")?100:36;
            if(!c.get(key).isIntegralNumber()||!c.get(key).canConvertToInt()||c.get(key).intValue()<1||c.get(key).intValue()>max)throw invalid(key);
        }
        identifier(c,"customerId");
        if(c.has("requestedMm")) {
            var n=c.get("requestedMm");if(!n.isNumber())throw invalid("requestedMm");
            BigDecimal mm=n.decimalValue();if(mm.compareTo(new BigDecimal("0.01"))<0||mm.compareTo(BigDecimal.ONE)>0||mm.stripTrailingZeros().scale()>2)throw invalid("requestedMm");
        }
        if(c.has("skillCodes")) {
            if(!c.get("skillCodes").isArray())throw invalid("skillCodes");
            Set<String> seen=new HashSet<>();for(var n:c.get("skillCodes"))if(!n.isTextual()||n.asText().isBlank()||n.asText().length()>30||!seen.add(n.asText()))throw invalid("skillCodes");
        }
    }
    public static Decision decision(ObjectNode node) {
        keys(node,Set.of("direction","decision","reason"));String direction=direction(node.path("direction").asText());
        String decision=node.path("decision").asText();if(!Set.of("ADOPT","REJECT").contains(decision))throw invalid("decision");
        String reason=null;if(node.has("reason")) {
            if(!node.get("reason").isTextual())throw invalid("reason");reason=node.get("reason").asText().strip();
            if(reason.isEmpty()||reason.length()>500)throw invalid("reason");
        }
        if(decision.equals("REJECT")&&reason==null)throw invalid("reason");
        return new Decision(direction,decision,reason);
    }
    public static HistoryQuery history(String owner,Map<String,String> q) {
        for(String key:q.keySet())if(!Set.of("page","size","direction","search","decisionStatus","fromYm","toYm").contains(key))throw invalid(key);
        int page=integer(q.getOrDefault("page","0"),"page"),size=integer(q.getOrDefault("size","50"),"size");
        if(page<0)throw invalid("page");if(!Set.of(20,50,100).contains(size))throw invalid("size");
        String d=q.get("direction"),s=q.get("decisionStatus");if(d!=null)direction(d);
        if(s!=null&&!Set.of("PENDING","ADOPT","REJECT").contains(s))throw invalid("decisionStatus");
        for(String k:List.of("fromYm","toYm"))if(q.containsKey(k))AiDataConventions.month(q.get(k),k);
        if(q.containsKey("fromYm")&&q.containsKey("toYm")&&q.get("fromYm").compareTo(q.get("toYm"))>0)throw invalid("fromYm");
        return new HistoryQuery(owner,page,size,d,q.get("search"),s,q.get("fromYm"),q.get("toYm"));
    }
    private static int integer(String s,String field) {try{return Integer.parseInt(s);}catch(NumberFormatException e){throw invalid(field);}}
    public static ApiException invalid(String field) {return ApiException.badRequest("입력값을 확인하세요.",field);}
}
