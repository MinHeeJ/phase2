package com.sicost.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.payments.FinancePaymentModels.*;

@Service
@Transactional(readOnly=true)
public class FinancePaymentService {
    private final FinancePaymentMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;
    private final ExcelExportService excel;
    private final Clock clock;
    public FinancePaymentService(FinancePaymentMapper mapper,CurrentUser user,PermissionService permissions,
            ObjectMapper json,ExcelExportService excel,Clock clock) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.json=json;this.excel=excel;this.clock=clock;
    }
    private void open(String month) {
        if("CLOSED".equals(mapper.monthStatus(month)))
            throw ApiException.conflict("CLOSED_MONTH","마감된 월은 변경할 수 없습니다.","month");
    }
    private Payment require(long id) {
        if(id<1) throw FinancePaymentInput.invalid("id");
        Payment p=mapper.row(id);
        if(p==null) throw ApiException.notFound("지급 건이 없습니다.");
        return p;
    }
    private Payment locked(long id) {
        if(id<1) throw FinancePaymentInput.invalid("id");
        Long root=mapper.root(id);
        if(root==null || mapper.lock(root)==null) throw ApiException.notFound("지급 건이 없습니다.");
        return require(id);
    }
    private void editable(Payment p) {
        if("PAID".equals(p.pay_stts_cd())) throw ApiException.conflict("ALREADY_PAID","이미 지급 완료한 건입니다.","pay_stts_cd");
        if(!"PLAN".equals(p.pay_stts_cd())) throw ApiException.conflict("STALE_PAYMENT","이월되거나 취소된 건입니다.","pay_stts_cd");
        open(p.pay_ym());
    }
    private void version(Payment p,LocalDateTime version) {
        if(version!=null && !Objects.equals(p.updated_at(),version))
            throw ApiException.conflict("STALE_PAYMENT","다른 변경이 있습니다. 다시 조회하세요.","updated_at");
    }
    private void choices(PaymentInput i) {
        open(i.pay_ym());
        if(!mapper.activeCategory(i.pay_se_cd())) throw FinancePaymentInput.invalid("pay_se_cd");
        if(!mapper.activeBank(i.bank())) throw FinancePaymentInput.invalid("bank");
        if(i.dev_id()!=null && !mapper.developer(i.dev_id())) throw FinancePaymentInput.invalid("dev_id");
        if(i.ptnr_id()!=null && !mapper.partner(i.ptnr_id())) throw FinancePaymentInput.invalid("ptnr_id");
        if("DEV".equals(i.rcvr_se_cd()) && i.dev_id()==null) throw FinancePaymentInput.invalid("dev_id");
        if("PARTNER".equals(i.rcvr_se_cd()) && i.ptnr_id()==null) throw FinancePaymentInput.invalid("ptnr_id");
    }
    private void recipient(Payment p) {
        if(p.recipient()==null || p.recipient().isBlank()) throw FinancePaymentInput.invalid("recipient");
        if(p.bank()==null || !mapper.activeBank(p.bank())) throw FinancePaymentInput.invalid("bank");
        if(p.account()==null || !p.account().matches("[0-9 -]{1,30}")) throw FinancePaymentInput.invalid("account");
        if(p.dpstr_nm()==null || p.dpstr_nm().isBlank()) throw FinancePaymentInput.invalid("dpstr_nm");
    }
    public PaymentList list(String month) {
        permissions.can("UI-20.read");FinancePaymentInput.month(month);
        List<Payment> rows=mapper.list(month);
        var grouped=new LinkedHashMap<Integer,List<Payment>>();
        for(var p:rows) grouped.computeIfAbsent(p.pay_odr_no()==null?0:p.pay_odr_no(),k->new ArrayList<>()).add(p);
        var summary=grouped.entrySet().stream().map(e->{
            long planned=0,paid=0,remaining=0;
            for(var p:e.getValue()) {
                paid=Math.addExact(paid,p.executed_amt());
                if(!"CANCEL".equals(p.pay_stts_cd()) && !"UNPAID".equals(p.pay_stts_cd())) {
                    planned=Math.addExact(planned,p.pay_amt());remaining=Math.addExact(remaining,p.remaining());
                }
            }
            return new PaymentSummary(e.getKey()==0?null:e.getKey(),e.getValue().size(),planned,paid,remaining);
        }).toList();
        var warnings=new ArrayList<String>();
        if(rows.isEmpty()) warnings.add("자료 없음");
        warnings.addAll(mapper.missingSources(month));
        if(rows.stream().anyMatch(p->p.bank()==null || p.account()==null || p.dpstr_nm()==null)) warnings.add("수령정보 누락: 완료·검토 내보내기 전에 입력하세요.");
        return new PaymentList(rows,rows.size(),summary,"CLOSED".equals(mapper.monthStatus(month)),warnings,mapper.categories(),mapper.banks());
    }
    @Transactional
    public Payment create(PaymentInput input) {
        permissions.can("UI-20.create");choices(input);
        long id=mapper.insert(new PaymentWrite(0,input,"MANUAL",null,null,user.userId()));
        var after=require(id);audit(null,after,"INSERT",null);return after;
    }
    @Transactional
    public Payment update(long id,PaymentInput input) {
        permissions.can("UI-20.update");var before=locked(id);editable(before);version(before,input.updated_at());choices(input);
        if(input.amount()<before.executed_amt()) throw FinancePaymentInput.invalid("pay_amt");
        if(before.cry_pay_id()!=null && input.amount()!=before.pay_amt()) throw ApiException.conflict("STALE_PAYMENT","이월 잔액은 변경할 수 없습니다.","pay_amt");
        mapper.update(new PaymentWrite(id,input,before.pay_src_cd(),before.src_rec_id(),before.cry_pay_id(),user.userId()));
        var after=require(id);audit(before,after,"UPDATE",null);return after;
    }
    @Transactional
    public Payment complete(long id,CompleteInput input) {
        permissions.can("UI-20.confirm");var before=locked(id);editable(before);version(before,input.updated_at());
        open(YearMonth.from(input.pay_dt()).toString());recipient(before);
        long amount=input.paid_amount()==null?before.remaining():input.paid_amount();
        if(amount<1 || amount>before.remaining()) throw FinancePaymentInput.invalid("paid_amount");
        mapper.execute(new PaymentExecution(0,id,input.pay_dt(),amount,null,null));
        mapper.state(new PaymentState(id,amount==before.remaining()?"PAID":"PLAN",amount==before.remaining()?input.pay_dt():null,user.userId()));
        mapper.taxActual(mapper.root(id));var after=require(id);audit(before,after,"UPDATE",null);return after;
    }
    @Transactional
    public Payment carry(long id,String month) {
        permissions.can("UI-20.update");FinancePaymentInput.month(month);var before=locked(id);editable(before);
        if(!month.equals(mapper.nextOpen(before.pay_ym()))) throw ApiException.conflict("INVALID_MONTH","다음 미마감 월을 선택하세요.","month");
        open(month);
        if(before.remaining()<1) throw FinancePaymentInput.invalid("pay_amt");
        var chain=mapper.chain(mapper.root(id));
        if(chain.stream().anyMatch(p->Objects.equals(p.cry_pay_id(),id) && !"CANCEL".equals(p.pay_stts_cd())))
            throw ApiException.conflict("STALE_PAYMENT","이미 이월한 건입니다.","month");
        YearMonth target=YearMonth.parse(month);
        var i=new PaymentInput(month,before.pay_odr_no(),before.pay_se_cd(),before.rcvr_se_cd(),before.dev_id(),before.ptnr_id(),
            before.recipient(),before.remaining(),0,0,target.atDay(Math.min(before.pay_plan_dt().getDayOfMonth(),target.lengthOfMonth())),
            before.bank(),before.account(),before.dpstr_nm(),before.remark(),null);
        long child=mapper.insert(new PaymentWrite(0,i,before.pay_src_cd(),before.src_rec_id(),id,user.userId()));
        mapper.state(new PaymentState(id,"UNPAID",null,user.userId()));
        audit(before,require(id),"UPDATE","이월");var after=require(child);audit(null,after,"INSERT","이월");return after;
    }
    @Transactional
    public Payment reopen(long id,String reason) {
        permissions.can("UI-20.update");
        if(reason==null || reason.isBlank() || reason.length()>500) throw FinancePaymentInput.invalid("reason");
        var before=locked(id);open(before.pay_ym());
        List<PaymentExecution> executions=mapper.executions(id);
        if(executions.isEmpty()) throw ApiException.conflict("STALE_PAYMENT","취소할 지급 실적이 없습니다.","pay_stts_cd");
        List<Payment> descendants=mapper.chain(id).stream().filter(p->p.pay_id()!=id && !"CANCEL".equals(p.pay_stts_cd())).toList();
        for(var p:descendants) {
            open(p.pay_ym());
            if("PAID".equals(p.pay_stts_cd()) || p.executed_amt()>0) throw ApiException.conflict("STALE_PAYMENT","이월 건에 지급 실적이 있습니다.","pay_stts_cd");
        }
        for(var e:executions) open(YearMonth.from(e.date()).toString());
        for(var p:descendants) {mapper.state(new PaymentState(p.pay_id(),"CANCEL",null,user.userId()));audit(p,require(p.pay_id()),"UPDATE",reason);}
        for(var e:executions) mapper.reverse(new PaymentExecution(0,id,e.date(),Math.negateExact(e.amount()),e.id(),reason));
        mapper.state(new PaymentState(id,"PLAN",null,user.userId()));mapper.taxActual(mapper.root(id));
        var after=require(id);audit(before,after,"UPDATE",reason);return after;
    }
    @Transactional
    public PaymentList generate(String month) {
        permissions.can("UI-20.create");FinancePaymentInput.month(month);open(month);
        for(var candidate:mapper.candidates(month)) {
            Long id=mapper.source(candidate);Payment before=id==null?null:locked(id);
            if(before!=null && (!"PLAN".equals(before.pay_stts_cd()) || before.executed_amt()!=0)) continue;
            mapper.generate(candidate);Long saved=mapper.source(candidate);
            if(saved!=null) audit(before,require(saved),before==null?"INSERT":"UPDATE",null);
        }
        return list(month);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public byte[] export(String month,List<Long> ids) {
        permissions.can("UI-20.download");FinancePaymentInput.month(month);
        if(ids==null || ids.isEmpty() || ids.size()!=new HashSet<>(ids).size() || ids.stream().anyMatch(i->i==null || i<1)) throw FinancePaymentInput.invalid("ids");
        var rows=new ArrayList<Payment>();long total=0;
        for(long id:ids) {
            var p=require(id);
            if(!p.pay_ym().equals(month)) throw ApiException.notFound("선택한 월의 지급 건이 아닙니다.");
            if(!"PLAN".equals(p.pay_stts_cd())) throw FinancePaymentInput.invalid("ids");
            recipient(p);rows.add(p);total=Math.addExact(total,p.remaining());
        }
        var columns=List.of(new ExportColumn<Payment>("recipient","수령인",true,false,Payment::recipient),
            new ExportColumn<Payment>("dpstr_nm","예금주",true,false,Payment::dpstr_nm),
            new ExportColumn<Payment>("bank","은행",true,false,Payment::bank),
            new ExportColumn<Payment>("account","계좌",true,false,Payment::account),
            new ExportColumn<Payment>("amount","지급 잔액",true,true,Payment::remaining));
        byte[] bytes=excel.create("이체 검토용 · 은행 제출용 아님 · "+month+" · 총 "+rows.size()+"건 · 총액 "+total+"원",
            "기본 사용자",LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,rows);
        mapper.access("이체 검토용 · "+rows.size()+"건");return bytes;
    }
    private void audit(Payment before,Payment after,String type,String reason) {
        var a=json.valueToTree(before);var b=json.valueToTree(after);
        b.fieldNames().forEachRemaining(field->{
            if(before==null || !Objects.equals(a.get(field),b.get(field)))
                mapper.audit(new PaymentAudit(after.pay_id(),type,field,before==null?null:a.get(field).toString(),b.get(field).toString(),reason));
        });
    }
}
