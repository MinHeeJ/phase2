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
    public PageResult<PartnerListItem> list(PageRequest page,String kind,String useYn,String q) {
        permissions.can("UI-15.read");
        var query=PartnerQuery.of(page,kind,useYn,q,LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        return new PageResult<>(mapper.list(query),mapper.count(query),query.page(),query.size());
    }
    public PartnerDetail get(long id) {
        permissions.can("UI-15.read");return require(mapper.detail(id));
    }
    private PartnerDetail require(PartnerDetail row) {
        if(row==null) throw ApiException.notFound("거래처가 없습니다.");return row;
    }
    public ApiException duplicate(String biz) {
        String name=mapper.duplicateName(biz);
        return ApiException.conflict("DUPLICATE_BIZ_NO","이미 등록된 사업자번호입니다. ("+(name==null?"기존 거래처":name)+")","biz_reg_no");
    }
    private void codes(PartnerSave input,PartnerSave before) {
        if(input.indty_cd()!=null && (before==null || !Objects.equals(before.indty_cd(),input.indty_cd())) && !mapper.activeCode(new PartnerCode("INDUSTRY",input.indty_cd()))) throw ApiException.badRequest("업종을 골라 주세요.","indty_cd");
        if(input.bank_cd()!=null && (before==null || !Objects.equals(before.bank_cd(),input.bank_cd())) && !mapper.activeCode(new PartnerCode("BANK",input.bank_cd()))) throw ApiException.badRequest("은행을 골라 주세요.","bank_cd");
    }
    @Transactional
    public PartnerDetail create(PartnerSave input) {
        permissions.can("UI-15.create");codes(input,null);
        if(mapper.duplicateName(input.biz_reg_no())!=null) throw duplicate(input.biz_reg_no());
        long id=mapper.nextId();String no="C-"+String.format(Locale.ROOT,"%03d",id);
        mapper.insert(new PartnerWrite(id,no,input,Set.of(),user.userId()));
        mapper.appendHistory(new PartnerHistory(Long.toString(id),"INSERT",null,null,null,user.userId()));
        return get(id);
    }
    @Transactional
    public PartnerDetail update(long id,PartnerSave input) {
        permissions.can("UI-15.update");
        PartnerDetail before=require(mapper.lock(id));codes(input,before.input());
        Map<String,String> old=values(before.input()),after=values(input);
        Set<String> changed=new LinkedHashSet<>();old.forEach((key,value)->{if(!Objects.equals(value,after.get(key)))changed.add(key);});
        if(changed.isEmpty()) return before;
        mapper.update(new PartnerWrite(id,before.ptnr_no(),input,changed,user.userId()));
        for(String key:changed) mapper.appendHistory(new PartnerHistory(Long.toString(id),"UPDATE",key,auditValue(key,old.get(key)),auditValue(key,after.get(key)),user.userId()));
        return get(id);
    }
    private static String auditValue(String key,String value) {return key.equals("acnt_no") && value!=null?value.substring(Math.max(0,value.length()-4)):value;}
    private static Map<String,String> values(PartnerSave r) {
        Map<String,String> v=new LinkedHashMap<>();
        v.put("ptnr_se_cd",r.ptnr_se_cd());v.put("ptnr_nm",r.ptnr_nm());v.put("ceo_nm",r.ceo_nm());v.put("indty_cd",r.indty_cd());v.put("chrg_nm",r.chrg_nm());v.put("chrg_tel_no",r.chrg_tel_no());v.put("rmrk_desc",r.rmrk_desc());v.put("txinv_email_addr",r.txinv_email_addr());v.put("bank_cd",r.bank_cd());v.put("acnt_no",r.acnt_no());v.put("dpstr_nm",r.dpstr_nm());return v;
    }
}
