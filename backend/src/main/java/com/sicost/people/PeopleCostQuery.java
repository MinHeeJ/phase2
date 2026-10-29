package com.sicost.people;

import com.sicost.common.api.ApiException;
import java.time.YearMonth;
import java.util.*;

public record PeopleCostQuery(int year, String view, String empType, Long partId, Long vendorId,
        String contractType, String job, boolean lossOnly, boolean overlapOnly,
        boolean excludeTurnkeyPrice, int page, int size, String sort) {
    public static PeopleCostQuery parse(Map<String,String> input, YearMonth now) {
        int year = integer(input.get("year"), "year", 1, now.getYear());
        String view = input.getOrDefault("view", "TOTAL");
        if (!Set.of("TOTAL", "AVERAGE").contains(view)) throw invalid("view");
        String emp = text(input, "empType");
        if (emp != null && !Set.of("FT", "CT", "FL", "OS").contains(emp)) throw invalid("empType");
        String type = text(input, "contractType");
        if (type != null && !Set.of("MO", "DP", "TK", "IN").contains(type)) throw invalid("contractType");
        String sort = input.getOrDefault("sort", "name,asc");
        if (!Set.of("name,asc", "name,desc", "profit,asc", "profit,desc", "cost,asc", "cost,desc").contains(sort)) throw invalid("sort");
        return new PeopleCostQuery(year, view, emp, id(text(input,"partId"),"partId"),
            id(text(input,"vendorId"),"vendorId"), type, text(input,"job"), bool(input,"lossOnly"),
            bool(input,"overlapOnly"), bool(input,"excludeTurnkeyPrice"),
            integer(input.getOrDefault("page","0"),"page",0,1000000),
            integer(input.getOrDefault("size","50"),"size",1,100),sort);
    }
    public static ApiException invalid(String field) {
        return new ApiException(400,"VALIDATION_ERROR","조회 조건을 확인하세요.",field);
    }
    public static Long id(String value, String field) {
        if (value == null) return null;
        try {
            if (!value.matches("[0-9]{1,19}")) throw new NumberFormatException();
            long result=Long.parseLong(value);
            if (result<1) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException ex) { throw invalid(field); }
    }
    private static int integer(String value,String field,int min,int max) {
        try {
            if (value == null || !value.matches("[0-9]{1,9}")) throw new NumberFormatException();
            int result=Integer.parseInt(value);
            if (result<min || result>max) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException ex) { throw invalid(field); }
    }
    private static boolean bool(Map<String,String> input,String field) {
        String value=input.getOrDefault(field,"false");
        if (!Set.of("true","false").contains(value)) throw invalid(field);
        return Boolean.parseBoolean(value);
    }
    private static String text(Map<String,String> input,String field) {
        String value=input.get(field);
        if (value == null || value.isEmpty()) return null;
        if (value.length()>100) throw invalid(field);
        return value;
    }
    public YearMonth through(YearMonth now) { return year==now.getYear()?now:YearMonth.of(year,12); }
}
