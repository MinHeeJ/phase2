package com.sicost.board;

import com.sicost.common.api.ApiException;
import java.time.LocalDate;
import java.util.*;
import static com.sicost.board.WorkforceBoardModels.*;

public final class WorkforceBoardInput {
    private WorkforceBoardInput() {}
    public static Query parse(Map<String,String> values, boolean export) {
        var allowed = new HashSet<>(Set.of("year","job","grade","skill","emp_se_cd","free_only"));
        if (!export) allowed.addAll(Set.of("sort","page","size"));
        values.keySet().forEach(k -> { if (!allowed.contains(k)) throw error(k); });
        int year = integer(values.get("year"),"year",1,9999);
        int page = integer(values.getOrDefault("page","0"),"page",0,Integer.MAX_VALUE);
        int size = integer(values.getOrDefault("size","50"),"size",20,100);
        if (!Set.of(20,50,100).contains(size)) throw error("size");
        String sort = values.getOrDefault("sort","dev_id,asc");
        if (!sort.matches("(dev_id|dev_nm|mng_no|job_cd|grd_cd),(asc|desc)")) throw error("sort");
        String free = values.getOrDefault("free_only","false");
        if (!Set.of("true","false").contains(free)) throw error("free_only");
        String emp = code(values,"emp_se_cd");
        if (emp != null && !Set.of("FT","CT","FL","OS").contains(emp)) throw error("emp_se_cd");
        return new Query(year,code(values,"job"),code(values,"grade"),code(values,"skill"),emp,
            Boolean.parseBoolean(free),sort,page,size,LocalDate.of(year,1,1),LocalDate.of(year,12,31));
    }
    private static int integer(String value, String field, int min, int max) {
        try {
            if (value == null || !value.matches("[0-9]{1,10}")) throw error(field);
            int n = Integer.parseInt(value);
            if (n < min || n > max) throw error(field);
            return n;
        } catch (NumberFormatException e) { throw error(field); }
    }
    private static String code(Map<String,String> values, String field) {
        String value = values.get(field);
        if (value == null || value.isEmpty()) return null;
        if (!value.matches("[A-Z0-9_]{1,30}")) throw error(field);
        return value;
    }
    public static ApiException error(String field) {
        return ApiException.badRequest("입력값을 확인하세요.",field);
    }
}
