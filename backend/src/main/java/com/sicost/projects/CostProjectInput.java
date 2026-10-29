package com.sicost.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.BillingPaging;
import com.sicost.common.validation.BillingValues;
import com.sicost.common.validation.CostValues;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import static com.sicost.projects.CostProjectModels.*;

public final class CostProjectInput {
    private CostProjectInput() {}
    public static int year(Map<String,String> q, Clock clock) {
        return CostValues.year(integer(q.get("year"), "year", null), clock);
    }
    public static long id(String value) {
        Long id = CostValues.optionalId(value, "id");
        if (id == null) throw BillingValues.invalid("id");
        return id;
    }
    public static Query query(Map<String,String> q, Clock clock) {
        for (String key : q.keySet()) if (!Set.of("year","type","partId","status","q","page","size","sort").contains(key))
            throw BillingValues.invalid(key);
        var paging = BillingPaging.of(integer(q.get("page"), "page", 0), integer(q.get("size"), "size", 50), q.get("sort"));
        paging.allowedSort(Set.of("prj_id","prj_no","prj_nm","cumulative_cost_amt","confirmed_supply_amt","profit_amt","margin_rt","budget_use_rt"), "prj_id,asc");
        return new Query(year(q,clock), choice(q.get("type"),"type",Set.of("TK","MO","DP","IN")),
            CostValues.optionalId(q.get("partId"),"partId"),
            choice(q.get("status"),"status",Set.of("PLAN","RUN","HOLD","END","LOST")),
            CostValues.optionalText(q.get("q")),paging);
    }
    private static String choice(String value, String field, Set<String> values) {
        value = CostValues.optionalText(value);
        if (value != null && !values.contains(value)) throw BillingValues.invalid(field);
        return value;
    }
    private static Integer integer(String value, String field, Integer fallback) {
        if (value == null) return fallback;
        try { return Integer.valueOf(value); }
        catch (NumberFormatException ex) { throw BillingValues.invalid(field); }
    }
    public static SimulationInput simulation(JsonNode node) {
        if (node == null || !node.isObject()) throw BillingValues.invalid(null);
        node.fieldNames().forEachRemaining(key -> {
            if (!Set.of("headcount_delta","monthly_cost_amt","months","monthly_price_amt").contains(key))
                throw BillingValues.invalid(key);
        });
        long delta = number(node,"headcount_delta",true);
        long months = number(node,"months",true);
        long cost = number(node,"monthly_cost_amt",true);
        Long price = node.has("monthly_price_amt") ? number(node,"monthly_price_amt",true) : null;
        if (delta < Integer.MIN_VALUE || delta > Integer.MAX_VALUE) throw BillingValues.invalid("headcount_delta");
        if (months <= 0 || months > Integer.MAX_VALUE) throw BillingValues.invalid("months");
        if (cost < 0) throw BillingValues.invalid("monthly_cost_amt");
        if (price != null && price < 0) throw BillingValues.invalid("monthly_price_amt");
        return new SimulationInput((int)delta,cost,(int)months,price);
    }
    private static long number(JsonNode node,String field,boolean required) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw BillingValues.invalid(field);
        return value.longValue();
    }
}
