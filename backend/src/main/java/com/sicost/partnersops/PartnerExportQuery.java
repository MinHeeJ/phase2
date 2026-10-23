package com.sicost.partnersops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.util.MultiValueMap;

public record PartnerExportQuery(String kind, String useYn, String q, List<String> columns, List<Long> ids, String order, LocalDate today) {
    public static final Map<String,String> LABELS;
    static {
        Map<String,String> m=new LinkedHashMap<>();
        m.put("ptnr_id","거래처 ID");m.put("ptnr_no","거래처 코드");m.put("ptnr_se_cd","구분 코드");m.put("kindName","구분");m.put("ptnr_nm","상호");m.put("biz_reg_no","사업자번호");m.put("ceo_nm","대표자");m.put("indty_cd","업종 코드");m.put("industryName","업종");m.put("chrg_nm","담당자");m.put("chrg_tel_no","연락처");m.put("txinv_email_addr","세금계산서 이메일");m.put("paymentAccount","지급 계좌");m.put("projectCount","진행 프로젝트");m.put("assignmentPeopleCount","투입 인력");m.put("use_yn","사용 여부");
        LABELS=Collections.unmodifiableMap(m);
    }
    public static long id(String value,String field) {
        if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw invalid(field);
        try { return Long.parseLong(value); } catch(NumberFormatException ex) { throw invalid(field); }
    }
    static ApiException invalid(String field) { return ApiException.badRequest("입력값을 확인하세요.",field); }
    public static List<Long> disableIds(JsonNode body) {
        if(body==null || !body.isObject()) throw invalid("ids");
        body.fieldNames().forEachRemaining(k->{if(!k.equals("ids"))throw invalid(k);});
        JsonNode ids=body.get("ids");
        if(ids==null || !ids.isArray() || ids.isEmpty()) throw invalid("ids");
        Set<Long> result=new LinkedHashSet<>();
        for(JsonNode n:ids) { if(!n.isTextual())throw invalid("ids");result.add(id(n.textValue(),"ids")); }
        return List.copyOf(result);
    }
    public static PartnerExportQuery from(MultiValueMap<String,String> params,LocalDate today) {
        String kind=params.getFirst("kind"),use=params.containsKey("useYn")?params.getFirst("useYn"):"Y",q=params.getFirst("q");
        if(kind!=null && !kind.isEmpty() && !Set.of("CUST","VEND","BOTH").contains(kind))throw invalid("kind");
        if(use!=null && !use.isEmpty() && !Set.of("Y","N").contains(use))throw invalid("useYn");
        List<String> columns=split(params,"columns");
        if(columns.isEmpty() || new HashSet<>(columns).size()!=columns.size() || !LABELS.keySet().containsAll(columns))throw invalid("columns");
        List<Long> ids=null;
        if(params.containsKey("ids")) {
            List<String> raw=split(params,"ids");if(raw.isEmpty())throw invalid("ids");
            ids=raw.stream().map(s->id(s,"ids")).distinct().toList();
        }
        String order="\"ptnr_nm\" ASC, \"ptnr_id\" ASC",sort=params.getFirst("sort");
        if(sort!=null) {
            String[] parts=sort.split(",",-1);
            if(parts.length!=2 || !LABELS.containsKey(parts[0]) || !Set.of("asc","desc").contains(parts[1]))throw invalid("sort");
            String key=parts[0];
            order=(key.equals("paymentAccount")?"bank_cd "+parts[1]+", acnt_no":'"'+key+'"')+" "+parts[1]+", \"ptnr_id\" ASC";
        }
        return new PartnerExportQuery(kind,use,q==null?null:q.trim(),columns,ids,order,today);
    }
    private static List<String> split(MultiValueMap<String,String> p,String key) {
        List<String> values=p.get(key);if(values==null)return Collections.emptyList();
        return values.stream().flatMap(v->Arrays.stream(v.split(",",-1))).toList();
    }
}
