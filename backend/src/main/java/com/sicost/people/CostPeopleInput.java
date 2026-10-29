package com.sicost.people;

import com.sicost.common.api.ApiException;
import com.sicost.common.api.BillingPaging;
import com.sicost.common.costallocation.CostAnalysisInput;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

public record CostPeopleInput(int year, String view, String empType, Long partId, String vendorId,
    String contractType, String job, boolean lossOnly, boolean overlapOnly,
    boolean excludeTurnkeyPrice, BillingPaging paging) {
    public static CostPeopleInput parse(Map<String, String> input, Clock clock) {
        String rawYear = input.get("year");
        int year;
        try {
            if (rawYear == null || !rawYear.matches("[0-9]{4}")) throw CostAnalysisInput.invalid("year");
            year = Integer.parseInt(rawYear);
        } catch (NumberFormatException ex) { throw CostAnalysisInput.invalid("year"); }
        if (year < 1 || year > YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).getYear())
            throw CostAnalysisInput.invalid("year");
        String view = choice(input, "view", Set.of("TOTAL", "AVERAGE"), "TOTAL");
        String emp = choice(input, "empType", Set.of("FT", "CT", "FL", "OS"), null);
        String contract = choice(input, "contractType", Set.of("MO", "DP", "TK", "IN", "IDLE"), null);
        var paging = CostAnalysisInput.paging(input);
        try { paging.allowedSort(SORTS, "dev_nm,asc"); }
        catch (ApiException ex) { throw CostAnalysisInput.invalid("sort"); }
        String job = input.get("job");
        if (job != null && !job.matches("[A-Z0-9_]{1,30}")) throw CostAnalysisInput.invalid("job");
        String vendor = input.get("vendorId");
        if (vendor != null && !"SELF".equals(vendor)) CostAnalysisInput.id(vendor, "vendorId");
        return new CostPeopleInput(year, view, emp, id(input, "partId"), vendor,
            contract, job, bool(input, "lossOnly"), bool(input, "overlapOnly"),
            bool(input, "excludeTurnkeyPrice"), paging);
    }
    public static final Set<String> SORTS = Set.of("dev_id", "dev_nm", "affiliation_name", "pstn_cd",
        "emp_se_cd", "mm_qty", "bill_untprc_amt", "slry_amt", "total_cost_amt", "personal_revenue_amt",
        "profit_amt", "margin_rt");
    private static Long id(Map<String, String> input, String field) {
        return input.containsKey(field) ? CostAnalysisInput.id(input.get(field), field) : null;
    }
    private static boolean bool(Map<String, String> input, String field) {
        String value = input.get(field);
        if (value == null) return false;
        if (!Set.of("true", "false").contains(value)) throw CostAnalysisInput.invalid(field);
        return Boolean.parseBoolean(value);
    }
    private static String choice(Map<String, String> input, String field, Set<String> values, String fallback) {
        String value = input.get(field);
        if (value == null) return fallback;
        if (!values.contains(value)) throw CostAnalysisInput.invalid(field);
        return value;
    }
}
