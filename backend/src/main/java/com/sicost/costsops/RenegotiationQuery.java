package com.sicost.costsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.costs.CostAllocationService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import static com.sicost.costsops.RenegotiationModels.*;

public final class RenegotiationQuery {
    private RenegotiationQuery() {}
    public static Query parse(Map<String, String> values) {
        int window = integer(values, "windowMonths", 6);
        if (!Set.of(3, 6, 12).contains(window)) throw error("windowMonths");
        BigDecimal margin = number(values, "marginBelow", "15");
        if (Set.of(10, 15, 20).stream().noneMatch(n -> margin.compareTo(BigDecimal.valueOf(n)) == 0)) {
            throw error("marginBelow");
        }
        BigDecimal raise = number(values, "raiseRate", "5");
        if (raise.signum() < 0 || raise.compareTo(BigDecimal.valueOf(50)) > 0) throw error("raiseRate");
        String employee = values.get("empType");
        if (employee != null && !Set.of("FT", "CT", "FL", "OS").contains(employee)) throw error("empType");
        Long project = CostAllocationService.projectId(values.get("projectId"));
        int page = integer(values, "page", 0), size = integer(values, "size", 20);
        if (page < 0) throw error("page");
        if (size < 1 || size > 200) throw error("size");
        String sort = values.getOrDefault("sort", "end_dt,asc");
        if (!Set.of("end_dt,asc", "end_dt,desc", "margin_rt,asc", "margin_rt,desc",
                "dev_nm,asc", "dev_nm,desc").contains(sort)) throw error("sort");
        return new Query(window, margin, raise, employee, project, page, size, sort);
    }
    private static int integer(Map<String, String> values, String field, int fallback) {
        if (!values.containsKey(field)) return fallback;
        try {
            String value = values.get(field);
            if (!value.matches("[0-9]{1,10}")) throw error(field);
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) { throw error(field); }
    }
    private static BigDecimal number(Map<String, String> values, String field, String fallback) {
        String value = values.getOrDefault(field, fallback);
        if (!value.matches("-?[0-9]{1,10}(\\.[0-9]{1,10})?")) throw error(field);
        return new BigDecimal(value);
    }
    static ApiException error(String field) {
        return new ApiException(400, "VALIDATION_ERROR", "조회 조건을 확인하세요.", field);
    }
}
