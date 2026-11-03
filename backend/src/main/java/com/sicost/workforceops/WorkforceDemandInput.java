package com.sicost.workforceops;

import com.sicost.common.api.ApiException;
import com.sicost.opportunitiesops.OpportunityForecastInput;
import com.sicost.opportunitiesops.OpportunityForecastModels.Query;
import java.time.Clock;
import java.util.*;

public final class WorkforceDemandInput {
    private WorkforceDemandInput() {}
    public static Query parse(Map<String,String> values, Clock clock) {
        String sort=values.getOrDefault("sort","month,asc");
        if (!sort.matches("(month|job_cd|grd_cd|supply_mm|demand_mm|remaining_mm|shortage_mm|available_mm|confirmed_mm|extension_mm|temporary_mm|utilization_rate|idle_cost|average_labor_cost),(asc|desc)"))
            throw ApiException.badRequest("정렬 항목과 방향을 확인하세요.","sort");
        var input=new HashMap<>(values);
        input.put("sort","month,asc");
        var query=OpportunityForecastInput.parse(input,clock);
        return new Query(query.months(),query.job(),query.grade(),sort,query.page(),query.size(),query.today());
    }
}
