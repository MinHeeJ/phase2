package com.sicost.billingsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service
@Transactional(readOnly=true)
public class BillingCommandService {
    private final BillingMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;
    private final Clock clock;
    private final BillingReceivableService receivables;
    public BillingCommandService(BillingMapper mapper,CurrentUser user,PermissionService permissions,
            ObjectMapper json,Clock clock,BillingReceivableService receivables) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.json=json;this.clock=clock;this.receivables=receivables;
    }
    private State require(long id) {
        State row=mapper.state(BillingInput.id(id));
        if(row==null)throw ApiException.notFound("청구가 없습니다.");
        return row;
    }
    private State locked(long id) {
        State initial=require(id);
        mapper.lockProject(initial.prj_id());
        long root=initial.orgnl_bill_id()==null?id:initial.orgnl_bill_id();
        if(mapper.lockBill(root)==null)throw BillingRules.stale();
        if(root!=id && mapper.lockBill(id)==null)throw BillingRules.stale();
        return require(id);
    }
    private void open(State s) {
        LocalDate fallback=s.stage_plan_dt();
        if("Y".equals(s.amnd_yn()) && s.bill_dt()==null && s.orgnl_bill_id()!=null) {
            State root=require(s.orgnl_bill_id());fallback=root.bill_dt()==null?root.stage_plan_dt():root.bill_dt();
        }
        YearMonth month=new BillingRules.Period(s.contract_type(),s.atrb_ym()==null?null:YearMonth.parse(s.atrb_ym()),s.bill_dt(),fallback).billingMonth();
        BillingRules.requireOpen(month,mapper.monthStatus(month.toString()),"month");
    }
    public Detail detail(long id) {
        State s=require(id);var lines=mapper.lines(id);List<String> warnings=new ArrayList<>();
        if(s.txinv_no()==null || s.txinv_no().isBlank())warnings.add("세금계산서 번호가 없습니다. 번호 없이 확정할 수 있습니다.");
        if(!"Y".equals(s.amnd_yn())) {
            long source=0;
            if("TK".equals(s.contract_type()) && s.bill_stg_id()!=null) {
                Stage stage=mapper.stage(s.bill_stg_id());if(stage!=null)source=stage.sply_amt();
            } else for(Line line:lines)source=BillingValues.add(source,line.bill_amt(),"sply_amt");
            if(source!=s.sply_amt())warnings.add("원천 상세 합과 공급가액이 다릅니다. 확인 후 확정할 수 있습니다.");
        }
        return new Detail(receivables.row(s),lines,mapper.receipts(id),
            mapper.amendments(id).stream().map(receivables::row).toList(),s.orgnl_bill_id(),mapper.history(id),List.copyOf(warnings));
    }
    private void limit(State root,long supply,long vat) {
        BillingRules.requireReceiptTarget(BillingRules.Status.valueOf(root.bill_stts_cd()),"Y".equals(root.amnd_yn()));
        Balance balance=mapper.balance(root.bill_id());
        if(supply>=0 || BigDecimal.valueOf(supply).abs().compareTo(BigDecimal.valueOf(balance.supply()))>0)
            throw BillingValues.invalid("sply_amt");
        if(vat>0)throw BillingValues.invalid("vat_amt");
        BillingRules.requireAdjustedTotal(balance.total(),BillingValues.add(supply,vat,"tot_amt"),balance.received());
    }
    @Transactional
    public Detail confirm(long id) {
        permissions.can(BillingActions.CONFIRM);
        State before=locked(id);
        BillingRules.requireConfirmation(BillingRules.Status.valueOf(before.bill_stts_cd()),before.bill_dt(),
            before.sply_amt(),before.vat_amt(),"Y".equals(before.amnd_yn()));
        open(before);
        if("Y".equals(before.amnd_yn())) {
            State root=require(before.orgnl_bill_id());open(root);limit(root,before.sply_amt(),before.vat_amt());
        }
        BillingRules.requireChanged(mapper.transition(new Transition(id,"DRAFT","CONFIRM",null,user.userId())));
        audit(id,"UPDATE",before,require(id),"청구 확정");
        return detail(id);
    }
    @Transactional
    public Detail cancel(long id,String reason) {
        permissions.can(BillingActions.CANCEL);
        if(reason==null || reason.isBlank() || reason.length()>500)throw BillingValues.invalid("reason");
        State before=locked(id);
        if(!Set.of("DRAFT","CONFIRM").contains(before.bill_stts_cd()))throw BillingRules.stale();
        open(before);
        if(mapper.receiptCount(id)>0 || mapper.activeAmendments(id)>0)throw BillingRules.stale();
        BillingRules.requireChanged(mapper.transition(new Transition(id,before.bill_stts_cd(),"CANCEL",reason,user.userId())));
        audit(id,"UPDATE",before,require(id),reason);
        return detail(id);
    }
    @Transactional
    public Detail amend(long id,Amendment input) {
        permissions.can(BillingActions.CREATE);
        if(input==null || input.reason()==null || input.reason().isBlank() || input.reason().length()>500)
            throw BillingValues.invalid("reason");
        YearMonth creationMonth=YearMonth.from(ProjectValues.today(clock));
        String prefix=BillingNumbers.prefix(creationMonth);mapper.lockNumber(prefix);
        State original=locked(id);open(original);limit(original,input.sply_amt(),input.vat_amt());
        if(input.bill_dt()!=null && "TK".equals(original.contract_type())) {
            YearMonth target=YearMonth.from(input.bill_dt());
            BillingRules.requireOpen(target,mapper.monthStatus(target.toString()),"bill_dt");
        }
        String number=BillingNumbers.allocated(creationMonth,mapper.maximumNumber(prefix));
        long created=mapper.insertAmendment(new AmendmentWrite(original,number,input,user.userId()));
        State after=require(created);
        audit(created,"INSERT",null,after,input.reason());
        return detail(created);
    }
    private void audit(long id,String type,State before,State after,String reason) {
        mapper.audit(new Audit(Long.toString(id),type,snapshot(before),snapshot(after),reason,user.userId()));
    }
    private String snapshot(State value) {
        if(value==null)return null;
        try{return json.writeValueAsString(value);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}
    }
}
