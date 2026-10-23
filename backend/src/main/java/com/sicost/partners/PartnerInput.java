package com.sicost.partners;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.util.Set;
import static com.sicost.partners.PartnerModels.*;

final class PartnerInput {
    private PartnerInput() {}
    static PartnerValue parse(JsonNode n, boolean create) {
        if(n==null || !n.isObject()) throw ApiException.badRequest("입력값을 확인하세요.",null);
        Set<String> allowed=Set.of("ptnr_se_cd","ptnr_nm","biz_reg_no","ceo_nm","indty_cd","chrg_nm","chrg_tel_no","rmrk_desc","txinv_email_addr","bank_cd","acnt_no","dpstr_nm");
        n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k)) throw ApiException.badRequest("변경할 수 없는 항목입니다.",k);});
        String kind=text(n,"ptnr_se_cd",true,10,"구분을 골라 주세요.");
        if(!Set.of("CUST","VEND","BOTH").contains(kind)) throw ApiException.badRequest("구분을 골라 주세요.","ptnr_se_cd");
        String name=text(n,"ptnr_nm",true,50,"상호를 입력해 주세요.");
        String biz=create?text(n,"biz_reg_no",true,12,"사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)"):null;
        if(create && !biz.matches("[0-9]{3}-[0-9]{2}-[0-9]{5}")) throw ApiException.badRequest("사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)","biz_reg_no");
        String email=null,bank=null,account=null,depositor=null;
        if(!kind.equals("VEND")) {
            email=text(n,"txinv_email_addr",true,100,"고객사는 세금계산서 이메일이 꼭 필요합니다.");
            if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw ApiException.badRequest("이메일 형식이 맞지 않습니다. (예: tax@company.co.kr)","txinv_email_addr");
        }
        if(!kind.equals("CUST")) {
            bank=text(n,"bank_cd",true,30,"협력사는 지급 계좌(은행·계좌번호·예금주)가 꼭 필요합니다.");
            account=text(n,"acnt_no",true,20,"협력사는 지급 계좌(은행·계좌번호·예금주)가 꼭 필요합니다.");
            if(!account.matches("[0-9-]{8,20}")) throw ApiException.badRequest("계좌번호를 숫자와 하이픈(-)으로 입력해 주세요.","acnt_no");
            depositor=text(n,"dpstr_nm",true,30,"협력사는 지급 계좌(은행·계좌번호·예금주)가 꼭 필요합니다.");
        }
        return new PartnerValue(kind,name,biz,text(n,"ceo_nm",false,20,"20자 이하로 입력해 주세요."),
            text(n,"indty_cd",false,30,"업종을 확인해 주세요."),text(n,"chrg_nm",false,20,"20자 이하로 입력해 주세요."),
            text(n,"chrg_tel_no",false,20,"20자 이하로 입력해 주세요."),text(n,"rmrk_desc",false,500,"500자 이하로 입력해 주세요."),email,bank,account,depositor);
    }
    private static String text(JsonNode n,String key,boolean required,int max,String message) {
        JsonNode v=n.get(key);
        if(v==null || v.isNull()) {if(required) throw ApiException.badRequest(message,key);return null;}
        if(!v.isTextual() || v.textValue().length()>max) throw ApiException.badRequest(message,key);
        String value=v.textValue().trim();
        if(value.isEmpty()) {if(required) throw ApiException.badRequest(message,key);return null;}
        return value;
    }
    static long id(String id) {
        try {if(!id.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();return Long.parseLong(id);}
        catch(NumberFormatException e) {throw ApiException.notFound("거래처가 없습니다.");}
    }
}
