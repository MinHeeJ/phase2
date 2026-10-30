package com.sicost.profit;

import com.sicost.common.api.ApiException;
import java.util.Set;

public final class MonthlyProfitInput {
    private MonthlyProfitInput() {}
    public static int integer(String value,String field,int min,int max) {
        try {int n=Integer.parseInt(value);if(n>=min&&n<=max)return n;}
        catch(NumberFormatException ignored) { }
        throw invalid(field);
    }
    public static String period(String value) {
        if(value==null||!Set.of("MONTH","QUARTER","YTD").contains(value))throw invalid("period");
        return value;
    }
    public static ApiException invalid(String field) {return new ApiException(400,"VALIDATION_ERROR","입력값을 확인하세요.",field);}
}
