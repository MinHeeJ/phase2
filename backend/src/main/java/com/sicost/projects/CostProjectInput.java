package com.sicost.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.costallocation.CostAnalysisInput;
import java.time.*;
import java.util.*;
import static com.sicost.projects.CostProjectModels.*;
import static com.sicost.common.costallocation.CostAnalysisInput.invalid;

public final class CostProjectInput {
    private CostProjectInput() {}
    public static Query query(Map<String,String> input, Clock clock) {
        String value=input.get("year");
        if(value==null || !value.matches("[0-9]{1,4}")) throw invalid("year");
        int year=Integer.parseInt(value);
        YearMonth reference=YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        if(year<1 || year>reference.getYear()) throw invalid("year");
        String month=YearMonth.of(year,year==reference.getYear()?reference.getMonthValue():12).toString();
        String type=choice(input.get("type"),"type",Set.of("TK","MO","DP","IN"));
        String status=choice(input.get("status"),"status",Set.of("PLAN","RUN","HOLD","END","LOST"));
        Long part=input.containsKey("partId")?CostAnalysisInput.id(input.get("partId"),"partId"):null;
        String q=input.get("q");
        if(q!=null) { q=q.trim(); if(q.length()>200) throw invalid("q"); if(q.isEmpty())q=null; }
        var paging=CostAnalysisInput.paging(input);
        try { paging.allowedSort(Set.of("prj_id","prj_no","prj_nm","cntrct_ty_cd","prj_stts_cd",
            "confirmed_supply_amt","total_cost_amt","profit_amt","margin_rt","budget_use_rt","actual_mm_qty","plan_mm_qty"),"prj_nm,asc"); }
        catch(ApiException ex) { throw invalid("sort"); }
        return new Query(year,YearMonth.of(year,1).toString(),month,type,part,status,q,paging);
    }
    private static String choice(String value,String field,Set<String> choices) {
        if(value!=null && !choices.contains(value))throw invalid(field); return value;
    }
    public static SimulationInput simulation(JsonNode node, String type) {
        if(node==null || !node.isObject())throw invalid("body");
        var allowed=Set.of("headcount_delta","monthly_cost_amt","months","monthly_price_amt");
        node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw invalid(key);});
        long head=integer(node,"headcount_delta");
        long cost=integer(node,"monthly_cost_amt"), months=integer(node,"months");
        if(head<Integer.MIN_VALUE || head>Integer.MAX_VALUE)throw invalid("headcount_delta");
        if(cost<0)throw invalid("monthly_cost_amt");
        if(months<1 || months>Integer.MAX_VALUE)throw invalid("months");
        Long price=null;
        if(Set.of("MO","DP").contains(type)) {
            price=integer(node,"monthly_price_amt"); if(price<0)throw invalid("monthly_price_amt");
        } else if(node.has("monthly_price_amt"))throw invalid("monthly_price_amt");
        return new SimulationInput((int)head,cost,(int)months,price);
    }
    private static long integer(JsonNode node,String key) {
        var value=node.get(key);
        if(value==null || !value.isIntegralNumber() || !value.canConvertToLong())throw invalid(key);
        return value.longValue();
    }
}
