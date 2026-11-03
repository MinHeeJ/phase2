package com.sicost.tempassignments;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import static com.sicost.tempassignments.TempAssignmentModels.*;

public final class TempAssignmentInput {
    private TempAssignmentInput() {}
    public static ApiException error(String field) { return ApiException.badRequest("입력값을 확인하세요.",field); }
    public static long id(long id) { if (id < 1) throw error("id"); return id; }
    public static String month(String value, String field) {
        if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw error(field);
        try { YearMonth.parse(value); } catch (java.time.DateTimeException e) { throw error(field); }
        return value;
    }
    public static Write parse(JsonNode n) {
        if (n == null || !n.isObject()) throw error(null);
        var allowed = Set.of("need_id","dev_id","bgn_ym","end_ym","mth_mm_qty","tmpr_asgn_src_cd","mtch_scr");
        n.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw error(k); });
        long need = number(n,"need_id"), dev = number(n,"dev_id");
        String start = month(text(n,"bgn_ym"),"bgn_ym"), end = month(text(n,"end_ym"),"end_ym");
        if (start.compareTo(end) > 0) throw error("end_ym");
        var mm = n.get("mth_mm_qty");
        if (mm == null || !mm.isNumber()) throw error("mth_mm_qty");
        BigDecimal qty = mm.decimalValue();
        if (qty.compareTo(new BigDecimal("0.01")) < 0 || qty.compareTo(BigDecimal.ONE) > 0
                || qty.stripTrailingZeros().scale() > 2) throw error("mth_mm_qty");
        String source = text(n,"tmpr_asgn_src_cd");
        if (!Set.of("MANUAL","NEXT").contains(source)) throw error("tmpr_asgn_src_cd");
        // mtch_scr is read-only: never accept a client score as storage truth.
        return new Write(need,dev,start,end,qty,source);
    }
    private static String text(JsonNode n,String key) {
        var v=n.get(key); if(v==null || !v.isTextual()) throw error(key); return v.textValue();
    }
    private static long number(JsonNode n,String key) {
        var v=n.get(key);
        if(v==null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue()<1) throw error(key);
        return v.longValue();
    }
    private static Long optionalId(Map<String,String> p,String key) {
        if(!p.containsKey(key)) return null;
        try { long v=Long.parseLong(p.get(key)); if(v<1) throw error(key); return v; }
        catch(NumberFormatException e) { throw error(key); }
    }
    private static int integer(Map<String,String> p,String key,int fallback) {
        try { int v=Integer.parseInt(p.getOrDefault(key,Integer.toString(fallback))); if(v<0) throw error(key); return v; }
        catch(NumberFormatException e) { throw error(key); }
    }
    public static Query query(Map<String,String> p) {
        var keys=Set.of("need_id","dev_id","oppt_id","status","from","to","sort","page","size");
        p.keySet().forEach(k -> { if(!keys.contains(k)) throw error(k); });
        String status=p.get("status"), from=p.get("from"), to=p.get("to");
        if(status!=null && !Set.of("ACTIVE","RELEASED","CONVERTED").contains(status)) throw error("status");
        if(from!=null) month(from,"from"); if(to!=null) month(to,"to");
        if(from!=null && to!=null && from.compareTo(to)>0) throw error("to");
        var columns=Map.ofEntries(Map.entry("id","t.tmpr_asgn_id"),Map.entry("need_id","t.need_id"),
            Map.entry("dev_id","t.dev_id"),Map.entry("bgn_ym","t.bgn_ym"),Map.entry("end_ym","end_ym"),
            Map.entry("mth_mm_qty","t.mm_qty"),Map.entry("tmpr_asgn_src_cd","t.tmpr_asgn_src_cd"),
            Map.entry("mtch_scr","t.mtch_scr"),Map.entry("status","t.tmpr_asgn_stts_cd"),Map.entry("asgn_id","t.asgn_id"));
        var pair=p.getOrDefault("sort","id,asc").split(",",-1);
        if(pair.length!=2 || !columns.containsKey(pair[0]) || !Set.of("asc","desc").contains(pair[1])) throw error("sort");
        int page=integer(p,"page",0), size=integer(p,"size",50);
        if(!Set.of(20,50,100).contains(size)) throw error("size");
        return new Query(optionalId(p,"need_id"),optionalId(p,"dev_id"),optionalId(p,"oppt_id"),status,from,to,
            columns.get(pair[0])+" "+pair[1]+" NULLS LAST, t.tmpr_asgn_id ASC",page,size,(long)page*size);
    }
}
