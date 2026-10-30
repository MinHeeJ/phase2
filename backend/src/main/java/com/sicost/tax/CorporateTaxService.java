package com.sicost.tax;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.finance.PaymentExecutionService;
import com.sicost.common.security.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.tax.CorporateTaxModels.*;

@Service
@Transactional(readOnly=true)
public class CorporateTaxService {
    private final CorporateTaxMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final PaymentExecutionService executions;
    private final ObjectMapper json;
    public CorporateTaxService(CorporateTaxMapper mapper,CurrentUser user,PermissionService permissions,
            PaymentExecutionService executions,ObjectMapper json) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.executions=executions;this.json=json;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Plan plan(int year) {
        permissions.can("UI-10.read");String key=CorporateTaxInput.yearKey(year);
        var annual=mapper.plan(key);
        if(annual==null) throw ApiException.notFound("연간 예상 법인세가 아직 입력되지 않았습니다.");
        var months=allocate(year,annual.annual_amount(),mapper.snapshots(key));
        return new Plan(year,annual.annual_amount(),annual.reason(),months,mapper.history(key),
            months.stream().anyMatch(m->!m.closed()) && months.stream().noneMatch(m->m.warning()!=null));
    }
    public List<Month> allocate(int year,Long annual,List<Snapshot> snapshots) {
        CorporateTaxInput.yearKey(year);
        var byMonth=new HashMap<String,Snapshot>();
        for(var row:snapshots) byMonth.put(row.month(),row);
        long fixed=0;int open=0;boolean missing=false;String last=null;
        for(int i=1;i<=12;i++) {
            String month=YearMonth.of(year,i).toString();var row=byMonth.get(month);
            if(row!=null && row.closed()) {
                if(row.amount()==null) missing=true;else fixed=Math.addExact(fixed,row.amount());
            } else {open++;last=month;}
        }
        Long remaining=annual==null || missing || annual<fixed?null:Math.subtractExact(annual,fixed);
        var result=new ArrayList<Month>();
        for(int i=1;i<=12;i++) {
            String month=YearMonth.of(year,i).toString();var row=byMonth.get(month);boolean closed=row!=null && row.closed();
            Long amount=closed?row.amount():remaining==null?null:remaining/open;
            if(!closed && remaining!=null && month.equals(last)) amount=Math.addExact(amount,remaining%open);
            String warning=closed && amount==null?"MISSING_CLOSED_SNAPSHOT":!closed && remaining==null?
                (missing?"MISSING_CLOSED_SNAPSHOT":annual==null?"MISSING_TAX_PLAN":"ANNUAL_BELOW_CLOSED"):null;
            result.add(new Month(month,closed,amount,warning));
        }
        return List.copyOf(result);
    }
    @Transactional
    public Plan savePlan(int year,PlanInput input) {
        permissions.can("UI-10.update");String key=CorporateTaxInput.yearKey(year);
        mapper.lockYear(key);var before=mapper.plan(key);var snapshots=mapper.snapshots(key);
        var months=allocate(year,input.annual_amount(),snapshots);
        if(months.stream().allMatch(Month::closed)) throw ApiException.conflict("CLOSED_MONTH","모든 월이 마감되어 예상액을 변경할 수 없습니다.","year");
        if(months.stream().anyMatch(m->"MISSING_CLOSED_SNAPSHOT".equals(m.warning())))
            throw ApiException.conflict("CLOSED_MONTH","마감 월할 스냅샷을 확인하세요.","annual_amount");
        long closed=0;
        for(var month:months) if(month.closed()) closed=Math.addExact(closed,month.amount());
        if(input.annual_amount()<closed) throw new ApiException(400,"VALIDATION_ERROR","예상액은 마감 반영액 합계 이상이어야 합니다.","annual_amount");
        var write=new PlanWrite(key,input,before==null?null:before.annual_amount(),user.userId());
        mapper.savePlan(write);mapper.appendHistory(write);
        return plan(year);
    }
    public Payments payments(int year) {
        permissions.can("UI-10.read");String key=CorporateTaxInput.yearKey(year);
        var rows=mapper.payments(key);var annual=mapper.plan(key);
        Long interim=rows.stream().filter(p->p.tax_pay_se_cd().equals("INTERIM")).map(Payment::planned_amount).filter(Objects::nonNull).findFirst().orElse(null);
        Long finalAmount=annual==null || interim==null?null:Math.max(0,Math.subtractExact(annual.annual_amount(),interim));
        var locked=rows.stream().filter(p->p.actual_date()!=null || "CLOSED".equals(mapper.monthStatus(YearMonth.from(p.planned_date()).toString())))
            .map(Payment::tax_pay_se_cd).toList();
        return new Payments(year,rows,List.of(new Suggestion("FINAL",YearMonth.of(Math.min(year+1,9999),3).atEndOfMonth(),finalAmount),
            new Suggestion("INTERIM",YearMonth.of(year,8).atEndOfMonth(),null)),locked);
    }
    @Transactional
    public Payments savePayments(int year,List<PaymentInput> input) {
        permissions.can("UI-10.update");String key=CorporateTaxInput.yearKey(year);
        mapper.lockYear(key);var existing=mapper.lockPayments(key);
        for(var row:input) {
            var before=existing.stream().filter(p->p.tax_pay_se_cd().equals(row.tax_pay_se_cd())).findFirst().orElse(null);
            if(before!=null) {open(before.planned_date());if(before.actual_date()!=null) open(before.actual_date());}
            open(row.planned_date());if(row.actual_date()!=null) open(row.actual_date());
            if(before!=null && before.actual_date()!=null && (!Objects.equals(before.planned_date(),row.planned_date())
                    || !Objects.equals(before.planned_amount(),row.planned_amount()) || !Objects.equals(before.actual_date(),row.actual_date())
                    || !Objects.equals(before.actual_amount(),row.actual_amount())))
                throw ApiException.conflict("ALREADY_PAID","실제 납부 기록은 지급 관리에서 취소한 뒤 변경하세요.","actual_amount");
        }
        for(var row:input) {
            var before=existing.stream().filter(p->p.tax_pay_se_cd().equals(row.tax_pay_se_cd())).findFirst().orElse(null);
            long id=mapper.savePayment(new PaymentWrite(key,row,user.userId()));
            if(row.actual_date()!=null && (before==null || before.actual_date()==null))
                executions.completeTax(id,row.actual_date(),row.actual_amount(),row.updated_at());
            var after=new Payment(id,row.tax_pay_se_cd(),row.planned_date(),row.planned_amount(),row.actual_date(),row.actual_amount());
            mapper.auditPayment(new PaymentAudit(id,snapshot(before),snapshot(after),user.userId()));
        }
        return payments(year);
    }
    private void open(LocalDate date) {
        String month=YearMonth.from(date).toString();
        if("CLOSED".equals(mapper.monthStatus(month))) throw ApiException.conflict("CLOSED_MONTH",month+"은 마감되었습니다.","planned_date");
    }
    private String snapshot(Payment row) {
        if(row==null)return null;
        try{return json.writeValueAsString(row);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}
    }
}
