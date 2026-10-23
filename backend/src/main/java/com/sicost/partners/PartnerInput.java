package com.sicost.partners;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.math.BigInteger;
import java.util.*;

final class PartnerInput {
    static final List<String> FIELDS=List.of("ptnr_se_cd","ptnr_nm","biz_reg_no","ceo_nm","indty_cd","chrg_nm","chrg_tel_no","rmrk_desc","txinv_email_addr","bank_cd","acnt_no","dpstr_nm");
    private PartnerInput() {}
    static Long id(String id) {
        if(id==null || !id.matches("[1-9][0-9]{0,18}") || new BigInteger(id).compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0)
            throw ApiException.badRequest("거래처 번호를 확인하세요.","id");
        return Long.valueOf(id);
    }
    static Map<String,String> parse(JsonNode node, boolean create) {
        if(node==null || !node.isObject()) throw ApiException.badRequest("입력값을 확인하세요.",null);
        node.fieldNames().forEachRemaining(k->{if(!FIELDS.contains(k)) throw ApiException.badRequest("변경할 수 없는 항목입니다.",k);});
        Map<String,String> values=new LinkedHashMap<>();
        String kind=text(node,"ptnr_se_cd",true,4,"구분을 골라 주세요.");
        if(!Set.of("CUST","VEND","BOTH").contains(kind)) throw ApiException.badRequest("구분을 골라 주세요.","ptnr_se_cd");
        values.put("ptnr_se_cd",kind);
        values.put("ptnr_nm",text(node,"ptnr_nm",true,50,"상호를 입력해 주세요."));
        if(create) {
            String biz=text(node,"biz_reg_no",true,12,"사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)");
            if(!biz.matches("[0-9]{3}-[0-9]{2}-[0-9]{5}")) throw ApiException.badRequest("사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)","biz_reg_no");
            values.put("biz_reg_no",biz);
        }
        for(String key:List.of("ceo_nm","chrg_nm","chrg_tel_no")) values.put(key,text(node,key,false,20,"20자까지 입력해 주세요."));
        values.put("indty_cd",text(node,"indty_cd",false,30,"업종을 확인해 주세요."));
        values.put("rmrk_desc",text(node,"rmrk_desc",false,500,"500자까지 입력해 주세요."));
        String email=null,bank=null,account=null,holder=null;
        if(!kind.equals("VEND")) {
            email=text(node,"txinv_email_addr",true,100,"고객사는 세금계산서 이메일이 꼭 필요합니다.");
            if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw ApiException.badRequest("이메일 형식이 맞지 않습니다. (예: tax@company.co.kr)","txinv_email_addr");
        }
        if(!kind.equals("CUST")) {
            bank=text(node,"bank_cd",true,30,"은행을 골라 주세요.");
            account=text(node,"acnt_no",true,20,"계좌번호를 숫자와 하이픈(-)으로 입력해 주세요.");
            if(!account.matches("[0-9-]{8,20}")) throw ApiException.badRequest("계좌번호를 숫자와 하이픈(-)으로 입력해 주세요.","acnt_no");
            holder=text(node,"dpstr_nm",true,30,"예금주를 입력해 주세요.");
        }
        values.put("txinv_email_addr",email);values.put("bank_cd",bank);values.put("acnt_no",account);values.put("dpstr_nm",holder);
        return values;
    }
    private static String text(JsonNode node,String key,boolean required,int max,String message) {
        JsonNode value=node.get(key);
        if(value==null || value.isNull() || (value.isTextual() && value.textValue().isBlank())) {
            if(required) throw ApiException.badRequest(message,key);
            return null;
        }
        if(!value.isTextual() || value.textValue().length()>max) throw ApiException.badRequest(message,key);
        return value.textValue();
    }
}
