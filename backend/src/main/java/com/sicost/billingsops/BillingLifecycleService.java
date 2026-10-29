package com.sicost.billingsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.*;
import com.sicost.common.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingReviewModels.*;

@Service
@Transactional(readOnly=true)
public class BillingLifecycleService {
    private final BillingReviewMapper mapper;
    private final BillingMutationService mutations;
    private final BillingReceivableService receivables;
    private final BillingAmountService amounts;
    private final PermissionService permissions;
    public BillingLifecycleService(BillingReviewMapper mapper, BillingMutationService mutations,
            BillingReceivableService receivables, BillingAmountService amounts, PermissionService permissions) {
        this.mapper=mapper; this.mutations=mutations; this.receivables=receivables;
        this.amounts=amounts; this.permissions=permissions;
    }
    private Bill require(long id) {
        var row=mapper.bill(id);
        if(row==null) throw ApiException.notFound("청구가 없습니다.");
        return row;
    }
    public Detail detail(long id) {
        var bill=require(id);
        var header=mapper.header(id);
        var details=mapper.details(id);
        List<String> warnings=new ArrayList<>();
        if(bill.txinv_no()==null || bill.txinv_no().isBlank()) warnings.add("세금계산서 번호가 없습니다. 발행은 기록만 합니다.");
        long basis=0;
        for(var line:details) basis=BillingRules.add(basis,line.bill_amt(),"details");
        if("N".equals(header.amnd_yn()) && !details.isEmpty() && basis!=bill.sply_amt()) {
            warnings.add("원천 상세 합과 수동 공급가액이 다릅니다. 확정할 수 있습니다.");
        }
        return new Detail(bill,details,mapper.receipts(id),mapper.amendments(id),
            header.orgnl_bill_id(),mapper.history(id),List.copyOf(warnings));
    }
    private void audit(long id,String field,String before,String after,String reason,String type) {
        mutations.audit(new Audit("bill.bill",Long.toString(id),type,field,before,after,reason,null));
    }
    private void adjustment(Bill original,long supply,long vat) {
        long current=BillingRules.add(original.sply_amt(),mapper.confirmedAdjustmentSupply(original.bill_id()),"sply_amt");
        if(BillingRules.add(current,supply,"sply_amt")<0) throw BillingRules.invalid("sply_amt");
        receivables.requireAdjustment(receivables.balance(original.bill_id()),BillingRules.add(supply,vat,"vat_amt"));
    }
    @Transactional
    public Detail confirm(long id) {
        BillingActions.can(permissions,"confirmBilling");
        var state=mutations.lock(id);
        BillingRules.requireDraft(state.bill_stts_cd());
        mutations.requireOpen(state);
        var bill=require(id);
        if(bill.bill_dt()==null) throw BillingRules.invalid("bill_dt");
        var header=mapper.header(id);
        boolean amendment="Y".equals(header.amnd_yn());
        amounts.total(bill.sply_amt(),bill.vat_amt(),amendment);
        if(!amendment && bill.sply_amt()<=0) throw BillingRules.invalid("sply_amt");
        if(amendment) {
            if(header.orgnl_bill_id()==null) throw BillingRules.stale();
            adjustment(require(header.orgnl_bill_id()),bill.sply_amt(),bill.vat_amt());
        }
        BillingRules.requireAffected(mapper.transition(new Transition(id,"DRAFT","CONFIRM",null)));
        audit(id,"bill_stts_cd","DRAFT","CONFIRM",null,"UPDATE");
        return detail(id);
    }
    @Transactional
    public Detail cancel(long id,String reason) {
        BillingActions.can(permissions,"cancelBilling");
        if(reason==null || reason.isBlank()) throw BillingRules.invalid("reason");
        BillingRules.text(reason,500,"reason");
        var state=mutations.lock(id);
        if(!Set.of("DRAFT","CONFIRM").contains(state.bill_stts_cd())) throw BillingRules.stale();
        mutations.requireOpen(state);
        if(!mapper.receipts(id).isEmpty() || mapper.confirmedAmendments(id)>0) {
            throw ApiException.conflict("STALE_BILL","입금 또는 확정 수정 청구가 있어 취소할 수 없습니다.",null);
        }
        BillingRules.requireAffected(mapper.transition(new Transition(id,state.bill_stts_cd(),"CANCEL",reason)));
        audit(id,"bill_stts_cd",state.bill_stts_cd(),"CANCEL",reason,"UPDATE");
        return detail(id);
    }
    @Transactional
    public Detail amend(long id,Amendment input) {
        BillingActions.can(permissions,"createBillingAmendment");
        var state=mutations.lock(id);
        if(!"CONFIRM".equals(state.bill_stts_cd()) || state.orgnl_bill_id()!=null) throw BillingRules.stale();
        var original=require(id);
        amounts.total(input.sply_amt(),input.vat_amt(),true);
        if(input.reason()==null || input.reason().isBlank()) throw BillingRules.invalid("reason");
        BillingRules.text(input.reason(),500,"reason");
        BillingRules.text(input.txinv_no(),50,"txinv_no");
        mutations.requireOpen(state);
        if("TK".equals(original.contract_type()) && input.bill_dt()!=null) {
            mutations.requireOpen(java.time.YearMonth.from(input.bill_dt()),"bill_dt");
        }
        adjustment(original,input.sply_amt(),input.vat_amt());
        long created=mapper.insertAmendment(new AmendmentWrite(original,mutations.nextNumber(),input));
        if(!"TK".equals(original.contract_type())) {
            mapper.amendmentDetail(new BillingReviewMapper.AmendmentDetail(created,original.atrb_ym(),
                input.sply_amt(),BillingRules.text("수정 청구",200,"reason")));
        }
        audit(created,"orgnl_bill_id",null,Long.toString(id),input.reason(),"INSERT");
        audit(created,"sply_amt",null,Long.toString(input.sply_amt()),input.reason(),"INSERT");
        audit(created,"vat_amt",null,Long.toString(input.vat_amt()),input.reason(),"INSERT");
        return detail(created);
    }
}
