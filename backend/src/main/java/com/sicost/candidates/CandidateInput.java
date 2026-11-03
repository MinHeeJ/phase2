package com.sicost.candidates;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.*;
import java.util.*;
import static com.sicost.candidates.CandidateModels.*;

public final class CandidateInput {
    private CandidateInput() {}
    static final Set<String> STATUSES = Set.of("NONE","DONE","INTV","HOLD","SIGNED");
    public static ApiException invalid(String field) { return ApiException.badRequest("입력값을 확인하세요.",field); }
    public static long id(long id) { if (id < 1) throw invalid("id"); return id; }
    public static CandidateWrite parse(JsonNode n) {
        if (n == null || !n.isObject()) throw invalid(null);
        Set<String> allowed = Set.of("cand_nm","cand_se_cd","vndr_ptnr_id","job_cd","grd_cd","skill",
            "hope_untprc_amt","avail_dt","cntct_stts_cd","tel_no","source","rmrk_desc");
        n.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw invalid(k); });
        String type = choice(text(n,"cand_se_cd",10,true),Set.of("FL","OS"),"cand_se_cd");
        Long vendor = number(n,"vndr_ptnr_id",1,false);
        if (type.equals("OS") && vendor == null) throw invalid("vndr_ptnr_id");
        String status = text(n,"cntct_stts_cd",10,false);
        status = status == null ? "NONE" : choice(status,STATUSES,"cntct_stts_cd");
        List<String> skills = new ArrayList<>();
        if (n.has("skill")) {
            if (!n.get("skill").isArray()) throw invalid("skill");
            for (var s : n.get("skill")) {
                if (!s.isTextual() || s.textValue().isBlank() || s.textValue().length()>30 || skills.contains(s.textValue())) throw invalid("skill");
                skills.add(s.textValue());
            }
        }
        return new CandidateWrite(text(n,"cand_nm",50,true),type,type.equals("FL") ? null : vendor,
            text(n,"job_cd",30,true),text(n,"grd_cd",30,false),List.copyOf(skills),
            number(n,"hope_untprc_amt",0,true),date(text(n,"avail_dt",10,true),"avail_dt"),status,
            text(n,"tel_no",30,false),text(n,"source",30,false),text(n,"rmrk_desc",Integer.MAX_VALUE,false));
    }
    private static String text(JsonNode n,String key,int max,boolean required) {
        var v=n.get(key);
        if (v==null || v.isNull()) { if(required) throw invalid(key); return null; }
        if(!v.isTextual()) throw invalid(key);
        String s=v.textValue().trim();
        if(s.length()>max || required && s.isEmpty()) throw invalid(key);
        return s.isEmpty() ? null : s;
    }
    private static Long number(JsonNode n,String key,long min,boolean required) {
        var v=n.get(key);
        if(v==null || v.isNull()) { if(required) throw invalid(key); return null; }
        if(!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue()<min) throw invalid(key);
        return v.longValue();
    }
    static String choice(String s,Set<String> allowed,String field) {
        if(s!=null && !allowed.contains(s)) throw invalid(field); return s;
    }
    static LocalDate date(String s,String field) {
        try { if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid(field); return LocalDate.parse(s); }
        catch(DateTimeException e) { throw invalid(field); }
    }
}
