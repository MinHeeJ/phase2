package com.sicost.projects;

import com.sicost.common.api.*;
import java.time.LocalDate;
import java.util.*;

public record ProjectQuery(LocalDate yearStart, LocalDate yearEnd, String type,
        String status, String part, String q, LocalDate today, int page, int size,
        long offset, String order) {
    public static ProjectQuery of(Map<String,String> parameters, LocalDate today) {
        Set<String> allowed = Set.of("year","type","status","part","q","page","size","sort");
        parameters.keySet().forEach(k -> {
            if (!allowed.contains(k)) throw ApiException.badRequest("조회 조건을 확인하세요.",k);
        });
        int year = integer(parameters,"year",today.getYear());
        if (year < 1 || year > 9999) throw ApiException.badRequest("연도를 확인하세요.","year");
        var paging = new ProjectPaging(integer(parameters,"page",0),integer(parameters,"size",50),parameters.get("sort"));
        String type = blank(parameters.get("type")), status = blank(parameters.get("status"));
        if (type != null && !Set.of("TK","MO","DP","IN").contains(type)) throw ApiException.badRequest("계약유형을 확인하세요.","type");
        if (status != null && !Set.of("PLAN","RUN","HOLD","END","LOST").contains(status)) throw ApiException.badRequest("상태를 확인하세요.","status");
        String part = blank(parameters.get("part"));
        if (part != null && !part.equals("NONE")) {
            try { if (!part.matches("[0-9]{1,19}") || Long.parseLong(part) < 1) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw ApiException.badRequest("영업 파트를 확인하세요.","part"); }
        }
        return new ProjectQuery(LocalDate.of(year,1,1),LocalDate.of(year,12,31),type,status,
            part,blank(parameters.get("q")),today,paging.page(),paging.size(),paging.offset(),paging.order());
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static int integer(Map<String,String> p, String key, int fallback) {
        if (!p.containsKey(key)) return fallback;
        try { return Integer.parseInt(p.get(key)); }
        catch (NumberFormatException e) { throw ApiException.badRequest("입력값을 확인하세요.",key); }
    }
}
