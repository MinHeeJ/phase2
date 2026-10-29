package com.sicost.common.costallocation;

import com.sicost.common.api.ApiException;
import com.sicost.common.api.BillingPaging;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;

public final class CostAnalysisInput {
    private CostAnalysisInput() {}
    public static ApiException invalid(String field) {
        return new ApiException(400, "VALIDATION_ERROR", "입력값을 확인하세요.", field);
    }
    public static String month(String input) {
        if (input == null || !input.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw invalid("month");
        try { YearMonth.parse(input); } catch (DateTimeParseException ex) { throw invalid("month"); }
        return input;
    }
    public static long id(String input, String field) {
        if (input == null || !input.matches("[1-9][0-9]{0,18}")) throw invalid(field);
        try { return Long.parseLong(input); } catch (NumberFormatException ex) { throw invalid(field); }
    }
    public static BillingPaging paging(Map<String, String> input) {
        try {
            return BillingPaging.of(integer(input, "page"), integer(input, "size"), input.get("sort"));
        } catch (ApiException ex) { throw invalid(ex.field()); }
    }
    private static Integer integer(Map<String, String> input, String field) {
        String value = input.get(field);
        if (value == null) return null;
        if (!value.matches("[0-9]{1,10}")) throw invalid(field);
        try { return Integer.valueOf(value); } catch (NumberFormatException ex) { throw invalid(field); }
    }
}
