package com.sicost.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CalendarValues;
import java.util.*;
import static com.sicost.admin.AdminModels.*;

final class AdminInput {
    private AdminInput() {}
    static void object(JsonNode n, String... allowed) {
        if (n == null || !n.isObject()) throw ApiException.badRequest("입력값을 확인하세요.", null);
        Set<String> keys = Set.of(allowed);
        n.fieldNames().forEachRemaining(k -> { if (!keys.contains(k)) throw ApiException.badRequest("변경할 수 없는 항목입니다.", k); });
    }
    static String text(JsonNode n, String key, boolean required, int max, String message) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            if (required) throw ApiException.badRequest(message, key);
            return null;
        }
        if (!v.isTextual() || v.textValue().length() > max || (required && v.textValue().isBlank())) throw ApiException.badRequest(message, key);
        return v.textValue();
    }
    static String optionalNonNull(JsonNode n, String key, int max) {
        if (n.has(key) && n.get(key).isNull()) throw ApiException.badRequest("입력값을 확인하세요.", key);
        return text(n, key, false, max, "입력값을 확인하세요.");
    }
    static void pattern(String value, String regex, String field) {
        if (value == null || !value.matches(regex)) throw ApiException.badRequest("입력값을 확인하세요.", field);
    }
    static CodeGroupCreate group(JsonNode n) {
        object(n, "cd_grp_id", "cd_grp_nm", "attr1_nm", "attr2_nm", "attr3_nm");
        String id = text(n,"cd_grp_id",true,30,"그룹 코드를 입력하세요.");
        pattern(id,"[A-Z_]{1,30}","cd_grp_id");
        return new CodeGroupCreate(id,text(n,"cd_grp_nm",true,100,"그룹명을 입력하세요."),optionalNonNull(n,"attr1_nm",50),optionalNonNull(n,"attr2_nm",50),optionalNonNull(n,"attr3_nm",50));
    }
    static List<CodeRow> codes(JsonNode n) {
        if (n == null || !n.isArray()) throw ApiException.badRequest("전체 코드 목록을 보내세요.","dtl_cd");
        List<CodeRow> rows = new ArrayList<>();
        for (int i=0;i<n.size();i++) {
            try { rows.add(code(n.get(i))); }
            catch (ApiException e) { throw new ApiException(e.status(),e.code(),e.getMessage(),"["+i+"]."+e.field()); }
        }
        return rows;
    }
    static CodeRow code(JsonNode n) {
        object(n,"dtl_cd","cd_nm","sort_seq","use_yn","attr1_val","attr2_val","attr3_val","aply_bgn_dt");
        String code = text(n,"dtl_cd",true,30,"코드를 입력하세요.");
        String name = text(n,"cd_nm",true,100,"이름을 입력하세요.");
        JsonNode seq=n.get("sort_seq");
        if(seq==null || !seq.isIntegralNumber() || !seq.canConvertToInt()) throw ApiException.badRequest("순서를 입력하세요.","sort_seq");
        String use=text(n,"use_yn",true,1,"사용 여부를 입력하세요."); pattern(use,"Y|N","use_yn");
        String date=text(n,"aply_bgn_dt",false,10,"올바른 날짜를 입력하세요.");
        return new CodeRow(code,name,seq.intValue(),use,text(n,"attr1_val",false,100,"100자 이하로 입력하세요."),text(n,"attr2_val",false,100,"100자 이하로 입력하세요."),text(n,"attr3_val",false,100,"100자 이하로 입력하세요."),date==null?null:CalendarValues.date(date,"aply_bgn_dt"));
    }
    static ExcelMapCreate mapping(JsonNode n) {
        object(n,"excel_lbl_nm","trns_cd_grp_id","trns_cd","map_role_cd","rmrk_desc");
        String label=text(n,"excel_lbl_nm",true,100,"엑셀 글자를 입력하세요.");
        String group=text(n,"trns_cd_grp_id",true,30,"대응할 코드를 고르세요.");
        String code=text(n,"trns_cd",true,30,"대응할 코드를 고르세요."); pattern(code,"[A-Z0-9_]{1,30}","trns_cd");
        String role=text(n,"map_role_cd",true,10,"주/보조를 고르세요."); pattern(role,"MAIN|SUB","map_role_cd");
        return new ExcelMapCreate(label,group,code,role,optionalNonNull(n,"rmrk_desc",200));
    }
    static ExcelMapPatch patch(JsonNode n) {
        object(n,"map_role_cd","rmrk_desc","use_yn");
        if(n.isEmpty()) throw ApiException.badRequest("변경할 항목을 입력하세요.",null);
        String role=optionalNonNull(n,"map_role_cd",10), memo=optionalNonNull(n,"rmrk_desc",200),use=optionalNonNull(n,"use_yn",1);
        if(n.has("map_role_cd")) pattern(role,"MAIN|SUB","map_role_cd");
        if(n.has("use_yn")) pattern(use,"Y|N","use_yn");
        Set<String> supplied=new HashSet<>(); n.fieldNames().forEachRemaining(supplied::add);
        return new ExcelMapPatch(role,memo,use,Set.copyOf(supplied));
    }
    static ParamValueSave value(JsonNode n) {
        object(n,"num_val","txt_val");
        if(n.size()!=1) throw ApiException.badRequest("올바른 값을 입력하세요.","num_val");
        if(n.has("num_val")) {
            if(!n.get("num_val").isNumber()) throw ApiException.badRequest("올바른 값을 입력하세요.","num_val");
            return new ParamValueSave(n.get("num_val").decimalValue(),null);
        }
        return new ParamValueSave(null,text(n,"txt_val",true,200,"올바른 값을 입력하세요."));
    }
}
