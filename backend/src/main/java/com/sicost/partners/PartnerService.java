package com.sicost.partners;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.partners.PartnerModels.*;

@Service
@Transactional(readOnly=true)
public class PartnerService {
    private final PartnerMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public PartnerService(PartnerMapper mapper,CurrentUser user,PermissionService permissions,Clock clock) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;
    }
    public PageResult<PartnerListItem> listPartners(Map<String,String> params) {
        permissions.can("UI-15.read");
        PartnerQuery query=PartnerQuery.of(params,LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        return new PageResult<>(mapper.list(query),mapper.count(query),query.page(),query.size());
    }
    public PartnerDetail getPartner(String id) {
        permissions.can("UI-15.read");return required(mapper.detail(PartnerInput.id(id)));
    }
    @Transactional
    public PartnerDetail createPartner(JsonNode node) {
        permissions.can("UI-15.create");Map<String,String> values=PartnerInput.parse(node,true);validateCodes(values);
        mapper.reserveBusinessNumber(values.get("biz_reg_no"));
        String duplicate=mapper.duplicateName(values.get("biz_reg_no"));
        if(duplicate!=null) throw ApiException.conflict("DUPLICATE_BIZ_NO","이미 등록된 사업자번호입니다. ("+duplicate+")","biz_reg_no");
        Long id=mapper.nextId();String no=String.format(Locale.ROOT,"C-%03d",id);
        mapper.insert(new PartnerWrite(id,no,values,Set.of(),user.userId()));
        mapper.appendHistory(new PartnerHistory(id.toString(),"INSERT",null,null,null,user.userId()));
        return required(mapper.detail(id));
    }
    @Transactional
    public PartnerDetail updatePartner(String id,JsonNode node) {
        permissions.can("UI-15.update");Long key=PartnerInput.id(id);
        PartnerDetail before=required(mapper.forUpdate(key));
        Map<String,String> values=PartnerInput.parse(node,false);validateCodes(values);
        Map<String,String> old=values(before);Set<String> changed=new LinkedHashSet<>();
        values.forEach((field,value)->{if(!Objects.equals(old.get(field),value)) changed.add(field);});
        if(changed.isEmpty()) return before;
        mapper.update(new PartnerWrite(key,before.ptnr_no(),values,changed,user.userId()));
        for(String field:changed) mapper.appendHistory(new PartnerHistory(id,"UPDATE",field,auditValue(field,old.get(field)),auditValue(field,values.get(field)),user.userId()));
        return required(mapper.detail(key));
    }
    private void validateCodes(Map<String,String> values) {
        if(values.get("indty_cd")!=null && !mapper.activeCode("INDUSTRY",values.get("indty_cd"))) throw ApiException.badRequest("업종을 확인해 주세요.","indty_cd");
        if(values.get("bank_cd")!=null && !mapper.activeCode("BANK",values.get("bank_cd"))) throw ApiException.badRequest("은행을 골라 주세요.","bank_cd");
    }
    private static PartnerDetail required(PartnerDetail row) {if(row==null) throw ApiException.notFound("거래처가 없습니다.");return row;}
    private static String auditValue(String field,String value) {return field.equals("acnt_no")&&value!=null?value.substring(Math.max(0,value.length()-4)):value;}
    private static Map<String,String> values(PartnerDetail row) {
        Map<String,String> values=new LinkedHashMap<>();
        values.put("ptnr_se_cd",row.ptnr_se_cd());values.put("ptnr_nm",row.ptnr_nm());values.put("ceo_nm",row.ceo_nm());values.put("indty_cd",row.indty_cd());
        values.put("chrg_nm",row.chrg_nm());values.put("chrg_tel_no",row.chrg_tel_no());values.put("rmrk_desc",row.rmrk_desc());values.put("txinv_email_addr",row.txinv_email_addr());
        values.put("bank_cd",row.bank_cd());values.put("acnt_no",row.acnt_no());values.put("dpstr_nm",row.dpstr_nm());return values;
    }
}
