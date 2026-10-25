package com.sicost.developers;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.DeveloperManagementNumbers;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.sicost.developers.DeveloperModels.*;

public final class DeveloperInput {
    private DeveloperInput() {}
    public static DeveloperSave parse(JsonNode n,boolean update) {
        if(n==null || !n.isObject())throw ApiException.badRequest("입력값을 확인하세요.",null);
        var allowed=Set.of("mng_no","dev_nm","emp_se_cd","main_job_cd","vndr_ptnr_id","pstn_cd","duty_se_cd","grd_cd","sub_job","skill","bsn_part_id","hire_dt","tel_no","email_addr","rmrk_desc","dflt_untprc_amt");
        n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw ApiException.badRequest("변경할 수 없는 항목입니다.",k);});
        String no=update?null:DeveloperManagementNumbers.supplied(text(n,"mng_no",20,false));
        String name=text(n,"dev_nm",50,true),emp=text(n,"emp_se_cd",10,true),job=text(n,"main_job_cd",30,true);
        if(!Set.of("FT","CT","FL","OS").contains(emp))throw ApiException.badRequest("직원구분을 골라 주세요.","emp_se_cd");
        String duty=text(n,"duty_se_cd",10,false);if(duty==null)duty="DIRECT";
        if(!Set.of("DIRECT","INDIRECT").contains(duty))throw ApiException.badRequest("업무 구분을 골라 주세요.","duty_se_cd");
        String email=text(n,"email_addr",100,false);
        if(email!=null && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw ApiException.badRequest("이메일 형식이 맞지 않습니다.","email_addr");
        Long partner=emp.equals("OS")?number(n,"vndr_ptnr_id",true):null;
        if(emp.equals("OS") && partner==null)throw ApiException.badRequest("외주는 협력사를 골라 주세요.","vndr_ptnr_id");
        String hire=text(n,"hire_dt",10,false);LocalDate date=null;
        try {if(hire!=null)date=LocalDate.parse(hire);}catch(DateTimeParseException e){throw ApiException.badRequest("올바른 날짜를 입력하세요.","hire_dt");}
        return new DeveloperSave(no,name,emp,job,partner,text(n,"pstn_cd",30,false),duty,text(n,"grd_cd",30,false),
            codes(n,"sub_job",update,job),codes(n,"skill",update,null),number(n,"bsn_part_id",true),date,
            text(n,"tel_no",30,false),email,text(n,"rmrk_desc",500,false),number(n,"dflt_untprc_amt",false));
    }
    private static String text(JsonNode n,String key,int max,boolean required) {
        JsonNode value=n.get(key);
        if(value==null || value.isNull()) {if(required)throw missing(key);return null;}
        if(!value.isTextual())throw ApiException.badRequest("입력값을 확인하세요.",key);
        String text=value.textValue().trim();
        if(text.length()>max)throw ApiException.badRequest(max+"자 이하로 입력해 주세요.",key);
        if(text.isEmpty()){if(required)throw missing(key);return null;}return text;
    }
    private static ApiException missing(String key) {
        return ApiException.badRequest(switch(key){case "dev_nm"->"이름을 입력해 주세요.";case "emp_se_cd"->"직원구분을 골라 주세요.";case "main_job_cd"->"주 직무를 골라 주세요.";default->"입력값을 확인하세요.";},key);
    }
    private static Long number(JsonNode n,String key,boolean positive) {
        JsonNode v=n.get(key);if(v==null || v.isNull())return null;
        if(!v.isIntegralNumber() || !v.canConvertToLong() || (positive && v.longValue()<1))throw ApiException.badRequest("입력값을 확인하세요.",key);
        return v.longValue();
    }
    private static List<String> codes(JsonNode n,String key,boolean required,String excluded) {
        JsonNode v=n.get(key);
        if(v==null && !required)return Collections.emptyList();
        if(v==null || !v.isArray())throw ApiException.badRequest("목록을 확인하세요.",key);
        Set<String> result=new TreeSet<>();
        for(JsonNode item:v) {
            if(!item.isTextual() || item.textValue().isBlank() || item.textValue().length()>30)throw ApiException.badRequest("코드를 확인하세요.",key);
            String code=item.textValue().trim();if(!code.equals(excluded))result.add(code);
        }
        return List.copyOf(result);
    }
    public static long id(long id) {if(id<1)throw ApiException.badRequest("개발자 번호를 확인하세요.","id");return id;}
}
