package com.sicost.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.ProjectValues;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.sicost.projects.ProjectModels.*;

public final class ProjectInput {
    private ProjectInput() {}
    public static ProjectSave parse(JsonNode n, boolean update) {
        if (n == null || !n.isObject()) throw invalid(null);
        Set<String> fields = new HashSet<>(Set.of("prj_nm","cust_ptnr_id","cntrct_ty_cd",
            "prj_stts_cd","win_prblt_rt","cntrct_dt","cntrct_bgn_dt","cntrct_end_dt",
            "prfm_bgn_dt","stlm_mthd_cd","pm_dev_id","free_sprt_yn","quot_amt",
            "init_cntrct_amt","trgt_mrgn_rt","trgt_cst_amt","indty_cd","duty_fld_nm",
            "prj_skl","prev_prj_id","rmrk_desc","bsn_part_id"));
        if (update) fields.addAll(Set.of("prj_no","cur_cntrct_amt","cur_end_dt","cntrct_mm_qty"));
        n.fieldNames().forEachRemaining(k -> { if (!fields.contains(k)) throw invalid(k); });
        String name = text(n,"prj_nm",100,true);
        String type = choice(text(n,"cntrct_ty_cd",10,true),Set.of("TK","MO","DP","IN"),"cntrct_ty_cd");
        String status = choice(text(n,"prj_stts_cd",10,true),
            update ? Set.of("PLAN","RUN","HOLD","END") : Set.of("PLAN","RUN"),"prj_stts_cd");
        LocalDate start = date(n,"cntrct_bgn_dt",true), end = date(n,"cntrct_end_dt",true);
        LocalDate performance = date(n,"prfm_bgn_dt",false);
        ProjectValues.period(start,end,update ? null : performance,null);
        if (update && performance != null && performance.isBefore(start)) throw invalid("prfm_bgn_dt");
        boolean internal = type.equals("IN");
        Long customer = internal ? null : id(n,"cust_ptnr_id");
        Long part = internal || update ? null : id(n,"bsn_part_id");
        if (!internal && customer == null) throw invalid("cust_ptnr_id");
        if (!internal && !update && part == null) throw invalid("bsn_part_id");
        Long amount = internal ? null : money(n,"init_cntrct_amt",1);
        if (!internal && amount == null) throw invalid("init_cntrct_amt");
        String free = text(n,"free_sprt_yn",1,false);
        if (free == null) free = "N";
        choice(free,Set.of("Y","N"),"free_sprt_yn");
        BigDecimal probability = percent(n,"win_prblt_rt");
        return new ProjectSave(name,customer,type,status,status.equals("PLAN") ? probability : null,
            date(n,"cntrct_dt",false),start,end,performance,part,
            ProjectValues.settlement(type,text(n,"stlm_mthd_cd",10,false)),id(n,"pm_dev_id"),free,
            internal ? null : money(n,"quot_amt",0),amount,
            internal ? null : percent(n,"trgt_mrgn_rt"),internal ? null : money(n,"trgt_cst_amt",0),
            text(n,"indty_cd",30,false),text(n,"duty_fld_nm",100,false),skills(n),
            id(n,"prev_prj_id"),text(n,"rmrk_desc",500,false));
    }
    private static ApiException invalid(String field) {
        return ApiException.badRequest("입력값을 확인하세요.",field);
    }
    private static String choice(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) throw invalid(field); return value;
    }
    private static String text(JsonNode n, String key, int max, boolean required) {
        var v = n.get(key);
        if (v == null || v.isNull()) { if (required) throw invalid(key); return null; }
        if (!v.isTextual()) throw invalid(key);
        String s = v.textValue().trim();
        if (s.length() > max || required && s.isEmpty()) throw invalid(key);
        return s.isEmpty() ? null : s;
    }
    private static LocalDate date(JsonNode n, String key, boolean required) {
        String s = text(n,key,10,required);
        try { return s == null ? null : LocalDate.parse(s); }
        catch (DateTimeException e) { throw invalid(key); }
    }
    private static Long id(JsonNode n, String key) {
        var v = n.get(key); if (v == null || v.isNull()) return null;
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 1) throw invalid(key);
        return v.longValue();
    }
    private static Long money(JsonNode n, String key, long minimum) {
        var v = n.get(key); if (v == null || v.isNull()) return null;
        if (!v.isIntegralNumber()) throw invalid(key);
        return ProjectValues.money(v.decimalValue(),minimum,key);
    }
    private static BigDecimal percent(JsonNode n, String key) {
        var v = n.get(key); if (v == null || v.isNull()) return null;
        if (!v.isNumber()) throw invalid(key);
        return ProjectValues.percent(v.decimalValue(),key);
    }
    private static List<String> skills(JsonNode n) {
        var v = n.get("prj_skl"); if (v == null) return Collections.emptyList();
        if (!v.isArray()) throw invalid("prj_skl");
        Set<String> result = new TreeSet<>();
        for (var item : v) {
            if (!item.isTextual() || !item.textValue().matches("[A-Z0-9_]{1,30}")) throw invalid("prj_skl");
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }
    public static long id(long value) {
        if (value < 1) throw invalid("id"); return value;
    }
}
