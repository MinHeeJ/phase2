package com.sicost.entries;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.entries.FinanceEntryModels.*;

@Service
@Transactional(readOnly=true)
public class FinanceEntryService {
    private final FinanceEntryMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;
    private final Clock clock;
    public FinanceEntryService(FinanceEntryMapper mapper,CurrentUser user,PermissionService permissions,ObjectMapper json,Clock clock) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.json=json;this.clock=clock;
    }
    private YearMonth period(String month) {
        var ym=FinanceEntryInput.month(month,"entry_ym");
        if(ym.isAfter(YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul")))))
            throw ApiException.conflict("INVALID_MONTH","미래월은 선택할 수 없습니다.","entry_ym");
        return ym;
    }
    private void open(String month) {
        period(month);
        if("CLOSED".equals(mapper.monthStatus(month)))throw ApiException.conflict("CLOSED_MONTH",month+"은 마감된 달이라 입력할 수 없습니다.","entry_ym");
    }
    private FinanceEntry require(long id,boolean lock) {
        if(id<1)throw FinanceEntryInput.invalid("id");
        var row=lock?mapper.lockRow(id):mapper.row(id);
        if(row==null)throw ApiException.notFound("재무 항목이 없습니다.");return row;
    }
    public EntryList list(String month) {
        permissions.can("UI-10.read");var ym=period(month);var items=mapper.list(month);
        var copied=new HashSet<>(mapper.copiedSources(month));
        String status=mapper.monthStatus(month);
        var drafts="CLOSED".equals(status)?Collections.<FinanceEntry>emptyList():mapper.list(ym.minusMonths(1).toString()).stream()
            .filter(r->"Y".equals(r.rpt_yn())&&!copied.contains(r.entry_id())).map(r->draft(r,ym,false,true)).toList();
        long amount=0,vat=0;for(var r:items){amount=Math.addExact(amount,r.entry_amt());vat=Math.addExact(vat,r.vat_amt());}
        return new EntryList(items,items.size(),items.size(),drafts,"CLOSED".equals(status)?"CLOSED":"OPEN",amount,vat,mapper.months(String.format("%04d",ym.getYear())));
    }
    public EntryPreview preview(String month,boolean includeNonRecurring,List<Long> ids) {
        permissions.can("UI-10.read");open(month);var ym=period(month);
        var previous=mapper.list(ym.minusMonths(1).toString());
        if(ids!=null)for(long id:ids)if(previous.stream().noneMatch(r->r.entry_id()==id))throw ApiException.notFound("전월 재무 항목이 없습니다.");
        var copied=new HashSet<>(mapper.copiedSources(month));
        return new EntryPreview(previous.stream().filter(r->includeNonRecurring||"Y".equals(r.rpt_yn()))
            .filter(r->ids==null||ids.contains(r.entry_id())).map(r->draft(r,ym,copied.contains(r.entry_id()),false)).toList());
    }
    private FinanceEntry draft(FinanceEntry r,YearMonth month,boolean copied,boolean auto) {
        LocalDate date=r.inout_dt()==null?null:month.atDay(Math.min(r.inout_dt().getDayOfMonth(),month.lengthOfMonth()));
        return new FinanceEntry(null,month.toString(),r.fin_se_cd(),r.item_nm(),r.entry_amt(),r.vat_amt(),date,r.rpt_yn(),"N",r.remark(),r.entry_id(),auto?"Y":"N",copied);
    }
    private EntryInput validate(EntryInput raw,FinanceEntry before) {
        var p=FinanceEntryInput.parse(json.valueToTree(raw));open(p.entry_ym());
        if(before!=null && before.copy_entry_id()!=null) {
            if(p.copy_entry_id()!=null&&!p.copy_entry_id().equals(before.copy_entry_id()))throw FinanceEntryInput.invalid("copy_entry_id");
            p=new EntryInput(p.entry_ym(),p.fin_se_cd(),p.item_nm(),p.entry_amt(),p.vat_amt(),p.inout_dt(),p.rpt_yn(),p.inout_cmpl_yn(),p.remark(),before.copy_entry_id(),before.auto_fill_yn());
        }
        if(p.inout_dt()!=null)open(YearMonth.from(p.inout_dt()).toString());
        Long source=p.copy_entry_id();
        if(source!=null) {
            boolean retained=before!=null&&source.equals(before.copy_entry_id());
            if(!retained) {
                var original=require(source,false);
                if(!original.entry_ym().equals(YearMonth.parse(p.entry_ym()).minusMonths(1).toString()))throw FinanceEntryInput.invalid("copy_entry_id");
                if("Y".equals(p.auto_fill_yn())&&!"Y".equals(original.rpt_yn()))throw FinanceEntryInput.invalid("auto_fill_yn");
            }
            if(!(retained&&before.entry_ym().equals(p.entry_ym()))&&mapper.copiedSources(p.entry_ym()).contains(source))throw duplicate();
        }
        return p;
    }
    private ApiException duplicate(){return ApiException.conflict("DUPLICATE_SOURCE","이미 불러온 전월 항목입니다.","copy_entry_id");}
    @Transactional
    public FinanceEntry create(EntryInput raw) {
        permissions.can("UI-10.create");var input=validate(raw,null);long id;
        try{id=mapper.insert(new EntryWrite(0,input,user.userId()));}catch(DuplicateKeyException e){throw duplicate();}
        var after=require(id,false);audit(id,"INSERT",null,after);return after;
    }
    @Transactional
    public FinanceEntry update(long id,EntryInput raw) {
        permissions.can("UI-10.update");var before=require(id,true);open(before.entry_ym());
        if(before.inout_dt()!=null)open(YearMonth.from(before.inout_dt()).toString());
        var input=validate(raw,before);
        try{if(mapper.update(new EntryWrite(id,input,user.userId()))!=1)throw ApiException.notFound("재무 항목이 없습니다.");}catch(DuplicateKeyException e){throw duplicate();}
        var after=require(id,false);audit(id,"UPDATE",before,after);return after;
    }
    @Transactional
    public EntryRemoval delete(long id) {
        permissions.can("UI-10.delete");var before=require(id,true);open(before.entry_ym());
        if(before.inout_dt()!=null)open(YearMonth.from(before.inout_dt()).toString());
        var removal=new EntryRemoval(id,user.userId());if(mapper.delete(removal)!=1)throw ApiException.notFound("재무 항목이 없습니다.");
        audit(id,"DELETE",before,null);return removal;
    }
    private String snapshot(FinanceEntry row) {
        if(row==null)return null;
        try{return json.writeValueAsString(row);}catch(JsonProcessingException e){throw new IllegalStateException(e);}
    }
    private void audit(long id,String type,FinanceEntry before,FinanceEntry after) {
        mapper.audit(new EntryAudit(Long.toString(id),type,snapshot(before),snapshot(after),user.userId()));
    }
}
