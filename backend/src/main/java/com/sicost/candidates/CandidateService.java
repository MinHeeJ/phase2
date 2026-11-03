package com.sicost.candidates;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.candidates.CandidateModels.*;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class CandidateService {
    private final CandidateMapper mapper;
    private final CurrentUser user;
    private final Clock clock;
    public CandidateService(CandidateMapper mapper, CurrentUser user, Clock clock) {
        this.mapper=mapper; this.user=user; this.clock=clock;
    }
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    public CandidatePage list(Map<String,String> values) {
        var query=CandidateQuery.of(values,today());
        var kpi=mapper.kpi(query);
        return new CandidatePage(mapper.list(query).stream().map(this::detail).toList(),
            query.page(),query.size(),kpi.candidate_count(),kpi);
    }
    public CandidateDetail get(long id) { return detail(require(id)); }
    private CandidateRow require(long id) {
        CandidateInput.id(id);
        var row=mapper.row(id);
        if(row==null) throw ApiException.notFound("후보가 없습니다.");
        return row;
    }
    private CandidateDetail detail(CandidateRow row) {
        return new CandidateDetail(row,mapper.skills(row.cand_id()),mapper.contacts(row.cand_id()),
            mapper.slots(row.cand_id()).stream().map(s -> matching(s,row.hope_untprc_amt())).toList());
    }
    public static CandidateMatchingSlot matching(CandidateSlotSource s,Long hope) {
        long price=s.monthly_price()==null?0:s.monthly_price(),cost=hope==null?0:hope;
        long expense=s.team_expense()==null?0:s.team_expense();
        BigDecimal margin=price<=0?null:BigDecimal.valueOf(price).subtract(BigDecimal.valueOf(cost))
            .subtract(BigDecimal.valueOf(expense)).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(price),2,RoundingMode.HALF_UP);
        var warnings=new ArrayList<String>();
        if(s.team_expense()==null) warnings.add("운영 기준 팀비 경비 없음: 0원으로 추정");
        if(price<=0) warnings.add("월 단가가 없어 이익률을 계산할 수 없습니다.");
        return new CandidateMatchingSlot(s.need_id(),s.oppt_id(),s.oppt_nm(),s.job_cd(),s.bgn_ym(),
            price,cost,expense,margin,true,List.copyOf(warnings));
    }
    private void choices(CandidateWrite input) {
        code("JOB",input.job_cd(),"job_cd"); code("GRADE",input.grd_cd(),"grd_cd");
        code("CANDIDATE_SOURCE",input.source(),"source");
        for(String skill:input.skill()) code("SKILL",skill,"skill");
        if(input.cand_se_cd().equals("OS") && (input.vndr_ptnr_id()==null || !mapper.activeVendor(input.vndr_ptnr_id())))
            throw CandidateInput.invalid("vndr_ptnr_id");
    }
    private void code(String group,String code,String field) {
        if(code!=null && !mapper.activeCode(new CandidateCode(group,code))) throw CandidateInput.invalid(field);
    }
    @Transactional
    public CandidateDetail create(CandidateWrite input) {
        choices(input);
        long id=mapper.nextId();
        String number=String.format(Locale.ROOT,"CAND-%04d",id);
        mapper.insert(new CandidateInsert(id,number,input,user.userId()));
        for(String skill:input.skill()) mapper.insertSkill(new CandidateChild(id,skill));
        for(var e:input.columns().entrySet()) history(id,"INSERT",e.getKey(),null,e.getValue());
        history(id,"INSERT","skill",null,String.join(",",input.skill()));
        return get(id);
    }
    @Transactional
    public CandidateDetail update(long id,CandidateWrite input) {
        CandidateInput.id(id);
        if(mapper.lock(id)==null) throw ApiException.notFound("후보가 없습니다.");
        var before=require(id);
        choices(input);
        var old=before.input(mapper.skills(id)).columns();
        var after=input.columns();
        if(before.dev_id()!=null || !mapper.contacts(id).isEmpty()) after.put("cntct_stts_cd",before.cntct_stts_cd());
        var changes=new LinkedHashMap<String,Object>();
        after.forEach((k,v) -> { if(!Objects.equals(old.get(k),v)) changes.put(k,v); });
        if(!changes.isEmpty()) {
            mapper.update(new CandidateUpdate(id,changes,user.userId()));
            changes.forEach((k,v) -> history(id,"UPDATE",k,old.get(k),v));
        }
        var oldSkills=mapper.skills(id);
        if(!new TreeSet<>(oldSkills).equals(new TreeSet<>(input.skill()))) {
            mapper.deleteSkills(id);
            for(String skill:input.skill()) mapper.insertSkill(new CandidateChild(id,skill));
            history(id,"UPDATE","skill",String.join(",",oldSkills),String.join(",",input.skill()));
        }
        return get(id);
    }
    private void history(long id,String type,String field,Object before,Object after) {
        mapper.appendHistory(new CandidateHistory(Long.toString(id),type,field,
            auditValue(field,before),auditValue(field,after),user.userId()));
    }
    private static String auditValue(String field,Object value) {
        if(value==null) return null;
        return field.equals("tel_no")?"***":value.toString();
    }
}
