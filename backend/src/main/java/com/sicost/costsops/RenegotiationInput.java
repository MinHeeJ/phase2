package com.sicost.costsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.costallocation.CostAnalysisInput;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import static com.sicost.costsops.RenegotiationModels.*;

public final class RenegotiationInput {
    private RenegotiationInput() {}
    public static final Set<String> SORT_FIELDS = Set.of("asgn_id", "dev_nm", "emp_se_cd", "prj_nm",
        "end_dt", "mm_qty", "bill_untprc_amt", "monthly_cost_amt", "margin_rt", "target_price_amt",
        "required_raise_rt", "annual_profit_increase_amt", "target_annual_profit_increase_amt");
    public static Query parse(Map<String, String> input) {
        var window = number(input, "windowMonths", "6");
        if (!Set.of(new BigDecimal("3"), new BigDecimal("6"), new BigDecimal("12")).contains(window))
            throw CostAnalysisInput.invalid("windowMonths");
        var margin = number(input, "marginBelow", "15");
        if (!(margin.compareTo(BigDecimal.TEN) == 0 || margin.compareTo(new BigDecimal("15")) == 0
                || margin.compareTo(new BigDecimal("20")) == 0)) throw CostAnalysisInput.invalid("marginBelow");
        var raise = number(input, "raiseRate", "5");
        if (raise.signum() < 0 || raise.compareTo(new BigDecimal("50")) > 0)
            throw CostAnalysisInput.invalid("raiseRate");
        String emp = input.get("empType");
        if (emp != null && !Set.of("FT", "CT", "FL", "OS").contains(emp))
            throw CostAnalysisInput.invalid("empType");
        Long project = input.containsKey("projectId") ? CostAnalysisInput.id(input.get("projectId"), "projectId") : null;
        var paging = CostAnalysisInput.paging(input);
        try { paging.allowedSort(SORT_FIELDS, "end_dt,asc"); }
        catch (ApiException ex) { throw CostAnalysisInput.invalid("sort"); }
        return new Query(window.intValueExact(), margin, raise, emp, project, paging);
    }
    private static BigDecimal number(Map<String, String> input, String field, String fallback) {
        String value = input.getOrDefault(field, fallback);
        if (value == null || value.length() > 30 || !value.matches("-?[0-9]+(\\.[0-9]+)?"))
            throw CostAnalysisInput.invalid(field);
        try { return new BigDecimal(value); }
        catch (NumberFormatException ex) { throw CostAnalysisInput.invalid(field); }
    }
}
