package com.sicost.workstatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import static com.sicost.workstatus.WorkStatusModels.*;

public final class WorkStatusInputParser {
    private WorkStatusInputParser() {}
    private static final Set<String> CODES=Set.of("VACATION","PARENTAL","UNPAID","LEAVE","RESIGN");
    private static final Set<String> FIELDS=Set.of("work_stts_cd","bgn_dt","end_dt","paid_yn","rmrk_desc");
    public static void code(String value,String field) {
        if(value==null || !CODES.contains(value))throw ApiException.badRequest("상태를 골라 주세요.",field);
    }
    public static long id(long value,String field) {
        if(value<1)throw ApiException.badRequest("식별자를 확인하세요.",field);return value;
    }
    private static String text(JsonNode node,String key) {
        JsonNode value=node.get(key);
        if(value==null || value.isNull())return null;
        if(!value.isTextual())throw ApiException.badRequest("입력값을 확인하세요.",key);
        return value.textValue();
    }
    private static LocalDate date(JsonNode node,String key) {
        String value=text(node,key);
        if(value==null)return null;
        try {
            if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new DateTimeParseException("date",value,0);
            return LocalDate.parse(value);
        } catch(DateTimeParseException ex) {throw ApiException.badRequest("날짜를 확인하세요.",key);}
    }
    public static WorkStatusInput parse(JsonNode node) {
        if(node==null || !node.isObject())throw ApiException.badRequest("입력값을 확인하세요.",null);
        node.fieldNames().forEachRemaining(key->{if(!FIELDS.contains(key))throw ApiException.badRequest("허용하지 않는 입력 항목입니다.",key);});
        return normalize(new WorkStatusInput(text(node,"work_stts_cd"),date(node,"bgn_dt"),date(node,"end_dt"),text(node,"paid_yn"),text(node,"rmrk_desc")));
    }
    public static WorkStatusInput normalize(WorkStatusInput input) {
        if(input==null)throw ApiException.badRequest("입력값을 확인하세요.",null);
        code(input.work_stts_cd(),"work_stts_cd");
        if(input.bgn_dt()==null)throw ApiException.badRequest("시작일을 입력해 주세요.","bgn_dt");
        if(input.paid_yn()!=null && !Set.of("Y","N").contains(input.paid_yn()))throw ApiException.badRequest("유급 여부를 확인하세요.","paid_yn");
        LocalDate end="RESIGN".equals(input.work_stts_cd())?null:input.end_dt();
        if(end!=null && end.isBefore(input.bgn_dt()))throw ApiException.badRequest("종료일은 시작일 이후여야 합니다.","end_dt");
        if(input.rmrk_desc()!=null && input.rmrk_desc().codePointCount(0,input.rmrk_desc().length())>500)throw ApiException.badRequest("메모는 500자 이하로 입력해 주세요.","rmrk_desc");
        return new WorkStatusInput(input.work_stts_cd(),input.bgn_dt(),end,
            "VACATION".equals(input.work_stts_cd()) && "Y".equals(input.paid_yn())?"Y":"N",input.rmrk_desc());
    }
}
