package com.sicost.partners;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.util.Set;
import static com.sicost.partners.PartnerModels.*;

public final class PartnerInput {
    private PartnerInput() {}
    public static PartnerSave parse(JsonNode n, boolean update) {
        if(n==null || !n.isObject()) throw ApiException.badRequest("입력값을 확인하세요.",null);
        Set<String> allowed=Set.of("ptnr_se_cd","ptnr_nm","biz_reg_no","ceo_nm","indty_cd","chrg_nm","chrg_tel_no","rmrk_desc","txinv_email_addr","bank_cd","acnt_no","dpstr_nm");
        n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k)) throw ApiException.badRequest("변경할 수 없는 항목입니다.",k);});
        String kind=text(n,"ptnr_se_cd",10,true,"구분을 골라 주세요.");
        if(!Set.of("CUST","VEND","BOTH").contains(kind)) throw ApiException.badRequest("구분을 골라 주세요.","ptnr_se_cd");
        String name=text(n,"ptnr_nm",50,true,"상호를 입력해 주세요.");
        String biz=update?null:text(n,"biz_reg_no",12,true,"사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)");
        if(!update && !biz.matches("[0-9]{3}-[0-9]{2}-[0-9]{5}")) throw ApiException.badRequest("사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)","biz_reg_no");
        String email=null,bank=null,account=null,depositor=null;
        if(!kind.equals("VEND")) {
            email=text(n,"txinv_email_addr",100,true,"고객사는 세금계산서 이메일이 꼭 필요합니다.");
            if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw ApiException.badRequest("이메일 형식이 맞지 않습니다. (예: tax@company.co.kr)","txinv_email_addr");
        }
        if(!kind.equals("CUST")) {
            bank=text(n,"bank_cd",30,true,"은행을 골라 주세요.");
            account=text(n,"acnt_no",20,true,"계좌번호를 숫자와 하이픈(-)으로 입력해 주세요.");
            if(!account.matches("[0-9-]{8,20}")) throw ApiException.badRequest("계좌번호를 숫자와 하이픈(-)으로 입력해 주세요.","acnt_no");
            depositor=text(n,"dpstr_nm",30,true,"예금주를 입력해 주세요.");
        }
        return new PartnerSave(kind,name,biz,text(n,"ceo_nm",20,false,null),text(n,"indty_cd",30,false,null),text(n,"chrg_nm",20,false,null),text(n,"chrg_tel_no",20,false,null),text(n,"rmrk_desc",500,false,null),email,bank,account,depositor);
    }
    private static String text(JsonNode n,String key,int max,boolean required,String message) {
        JsonNode value=n.get(key);
        if(value==null || value.isNull()) {if(required) throw ApiException.badRequest(message,key);return null;}
        if(!value.isTextual()) throw ApiException.badRequest("입력값을 확인하세요.",key);
        String text=value.textValue().trim();
        if(text.length()>max) throw ApiException.badRequest(max+"자 이하로 입력해 주세요.",key);
        if(text.isEmpty()) {if(required) throw ApiException.badRequest(message,key);return null;}
        return text;
    }
}
