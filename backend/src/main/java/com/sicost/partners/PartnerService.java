package com.sicost.partners;

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
    public PageResult<PartnerListItem> list(Map<String,String> input) {
        permissions.can("UI-15.read");
        PartnerQuery q=PartnerQuery.of(input,LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        return new PageResult<>(mapper.list(q),mapper.count(q),q.page(),q.size());
    }
    public PartnerDetail get(String id) {
        permissions.can("UI-15.read");
        PartnerDetail row=mapper.detail(PartnerInput.id(id));
        if(row==null) throw ApiException.notFound("거래처가 없습니다.");
        return row;
    }
    @Transactional
    public PartnerDetail create(PartnerValue input) {
        permissions.can("UI-15.create");validateCodes(input,null);
        String duplicate=mapper.duplicateName(input.biz_reg_no());
        if(duplicate!=null) throw duplicate(duplicate);
        long id=mapper.nextId();
        mapper.insert(new PartnerWrite(id,code(id),input,Set.of(),user.userId()));
        mapper.appendHistory(new PartnerHistory(Long.toString(id),"INSERT",null,null,null,user.userId()));
        return get(Long.toString(id));
    }
    public ApiException duplicateConflict(String biz) {
        return duplicate(mapper.duplicateName(biz));
    }
    private static ApiException duplicate(String name) {
        return ApiException.conflict("DUPLICATE_BIZ_NO","이미 등록된 사업자번호입니다. ("+(name==null?"거래처":name)+")","biz_reg_no");
    }
    static String code(long id) {return "C-"+String.format(Locale.ROOT,"%03d",id);}
    @Transactional
    public PartnerDetail update(String id,PartnerValue input) {
        permissions.can("UI-15.update");
        long key=PartnerInput.id(id);
        PartnerDetail before=mapper.original(key);
        if(before==null) throw ApiException.notFound("거래처가 없습니다.");
        validateCodes(input,before);
        Map<String,String> b=values(before),a=values(input);
        Set<String> changed=new LinkedHashSet<>();
        a.forEach((k,v)->{if(!Objects.equals(b.get(k),v)) changed.add(k);});
        if(!changed.isEmpty()) {
            mapper.update(new PartnerWrite(key,before.ptnr_no(),input,changed,user.userId()));
            for(String field:changed) mapper.appendHistory(new PartnerHistory(id,"UPDATE",field,
                field.equals("acnt_no")?lastFour(b.get(field)):b.get(field),field.equals("acnt_no")?lastFour(a.get(field)):a.get(field),user.userId()));
        }
        return get(id);
    }
    private void validateCodes(PartnerValue input,PartnerDetail before) {
        if(input.indty_cd()!=null && (before==null || !Objects.equals(input.indty_cd(),before.indty_cd())) && !mapper.activeCode("INDUSTRY",input.indty_cd())) throw ApiException.badRequest("업종을 확인해 주세요.","indty_cd");
        if(input.bank_cd()!=null && (before==null || !Objects.equals(input.bank_cd(),before.bank_cd())) && !mapper.activeCode("BANK",input.bank_cd())) throw ApiException.badRequest("은행을 골라 주세요.","bank_cd");
    }
    static String lastFour(String value) {return value==null?null:value.substring(Math.max(0,value.length()-4));}
    private static Map<String,String> values(PartnerDetail row) {
        return values(new PartnerValue(row.ptnr_se_cd(),row.ptnr_nm(),null,row.ceo_nm(),row.indty_cd(),row.chrg_nm(),row.chrg_tel_no(),row.rmrk_desc(),row.txinv_email_addr(),row.bank_cd(),row.acnt_no(),row.dpstr_nm()));
    }
    private static Map<String,String> values(PartnerValue v) {
        Map<String,String> result=new LinkedHashMap<>();
        result.put("ptnr_se_cd",v.ptnr_se_cd());result.put("ptnr_nm",v.ptnr_nm());result.put("ceo_nm",v.ceo_nm());result.put("indty_cd",v.indty_cd());
        result.put("chrg_nm",v.chrg_nm());result.put("chrg_tel_no",v.chrg_tel_no());result.put("rmrk_desc",v.rmrk_desc());
        result.put("txinv_email_addr",v.txinv_email_addr());result.put("bank_cd",v.bank_cd());result.put("acnt_no",v.acnt_no());result.put("dpstr_nm",v.dpstr_nm());return result;
    }
}
