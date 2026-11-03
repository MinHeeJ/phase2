package com.sicost.candidates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.developers.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.candidates.CandidateModels.*;

@Service
@Transactional(readOnly=true)
public class CandidateService {
    private final CandidateMapper mapper;
    private final DeveloperService developers;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public CandidateService(CandidateMapper mapper,DeveloperService developers,CurrentUser user,PermissionService permissions,Clock clock) {
        this.mapper=mapper;this.developers=developers;this.user=user;this.permissions=permissions;this.clock=clock;
    }
    private LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    private CandidateRow require(long id,boolean lock) {
        CandidateInput.id(id);var row=lock?mapper.lock(id):mapper.row(id);
        if(row==null)throw ApiException.notFound("후보가 없습니다.");return row;
    }
    public CandidatePage list(Map<String,String> parameters) {
        permissions.can("UI-33.read");var q=CandidateQuery.of(parameters,today());
        return new CandidatePage(mapper.list(q).stream().map(this::detail).toList(),q.page(),q.size(),mapper.count(q),mapper.kpi(q));
    }
    public CandidateDetail get(long id){permissions.can("UI-33.read");return detail(require(id,false));}
    private CandidateDetail detail(CandidateRow row) {
        // Sensitive is a marker, not a serializer: never return the stored phone verbatim.
        var safe=new CandidateRow(row.cand_id(),row.cand_no(),row.cand_nm(),row.cand_se_cd(),row.vndr_ptnr_id(),row.job_cd(),row.grd_cd(),
            row.hope_untprc_amt(),row.avail_dt(),row.cntct_stts_cd(),row.tel_no()==null?null:"***",row.source(),row.rmrk_desc(),row.use_yn(),row.dev_id(),row.last_cntct_dt(),row.created_at());
        var slots=mapper.slots(row.cand_id()).stream().map(s->{
            long price=s.monthly_price()==null?0:s.monthly_price(),hope=row.hope_untprc_amt()==null?0:row.hope_untprc_amt();
            long expense=s.team_expense()==null?0:s.team_expense().setScale(0,RoundingMode.FLOOR).longValueExact();
            BigDecimal margin=price<=0?null:BigDecimal.valueOf(price).subtract(BigDecimal.valueOf(hope)).subtract(BigDecimal.valueOf(expense)).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(price),2,RoundingMode.HALF_UP);
            return new CandidateMatchingSlot(s.need_id(),s.oppt_id(),s.oppt_nm(),s.job_cd(),s.bgn_ym(),price,hope,expense,margin,true,
                s.team_expense()==null?List.of("팀비·경비 운영 기준 없음 — 0원 추정"):List.of("예상 이익률"));
        }).toList();
        return new CandidateDetail(safe,mapper.skills(row.cand_id()),mapper.contacts(row.cand_id()),slots);
    }
    private void code(String group,String code,String previous,String field) {
        if(code!=null&&!Objects.equals(code,previous)&&!mapper.activeCode(new Code(group,code)))throw ApiException.badRequest("사용 중인 코드를 골라 주세요.",field);
    }
    private void validate(CandidateSave input,CandidateRow old) {
        code("JOB",input.job_cd(),old==null?null:old.job_cd(),"job_cd");
        code("GRADE",input.grd_cd(),old==null?null:old.grd_cd(),"grd_cd");
        code("CANDIDATE_SOURCE",input.source(),old==null?null:old.source(),"source");
        var previous=old==null?Collections.<String>emptyList():mapper.skills(old.cand_id());
        for(String skill:input.skill())code("SKILL",skill,previous.contains(skill)?skill:null,"skill");
        if(input.cand_se_cd().equals("OS")&&!mapper.activePartner(input.vndr_ptnr_id()))throw ApiException.badRequest("사용 중인 협력사를 골라 주세요.","vndr_ptnr_id");
    }
    private void skills(long id,List<String> values){mapper.deleteSkills(id);for(String s:values)mapper.insertSkill(new CandidateChild(id,s));}
    @Transactional
    public CandidateDetail create(JsonNode body) {
        permissions.can("UI-33.create");var input=CandidateInput.parse(body);validate(input,null);
        long id=mapper.nextId();mapper.insert(new CandidateWrite(id,String.format(Locale.ROOT,"CAND-%04d",id),input,user.userId()));
        skills(id,input.skill());mapper.audit(new CandidateAudit(Long.toString(id),"INSERT",null,null,null,user.userId()));
        return get(id);
    }
    @Transactional
    public CandidateDetail update(long id,JsonNode body) {
        permissions.can("UI-33.update");var old=require(id,true);var input=CandidateInput.parse(body);validate(input,old);
        // A masked value is never written over the source contact by a read/edit cycle.
        String phone=!body.has("tel_no")||"***".equals(input.tel_no())?old.tel_no():input.tel_no();
        input=new CandidateSave(input.cand_nm(),input.cand_se_cd(),input.vndr_ptnr_id(),input.job_cd(),input.grd_cd(),input.skill(),input.hope_untprc_amt(),input.avail_dt(),input.cntct_stts_cd(),phone,input.source(),input.rmrk_desc());
        mapper.update(new CandidateWrite(id,old.cand_no(),input,user.userId()));skills(id,input.skill());mapper.projectLatestContact(id);
        mapper.audit(new CandidateAudit(Long.toString(id),"UPDATE",null,null,"후보 기본정보·스킬 변경",user.userId()));
        return get(id);
    }
    @Transactional
    public CandidateDetail contact(long id,JsonNode body) {
        permissions.can("UI-33.update");var input=CandidateInput.contact(body,today());require(id,true);
        mapper.appendContact(new ContactWrite(id,input,user.userId()));mapper.projectLatestContact(id);
        mapper.audit(new CandidateAudit(Long.toString(id),"UPDATE","last_cntct_dt",null,input.cntct_dt().toString(),user.userId()));return get(id);
    }
    @Transactional
    public CandidateConversionResult convert(long id,JsonNode body) {
        permissions.can("UI-33.convert");CandidateInput.object(body,Set.of("developer"));var row=require(id,true);
        if(row.dev_id()!=null)throw ApiException.conflict("ALREADY_CONVERTED","이미 개발자로 등록된 후보입니다.","id");
        if(!(body.get("developer") instanceof ObjectNode supplied))throw CandidateInput.invalid("developer");
        ObjectNode n=supplied.deepCopy();n.put("dev_nm",row.cand_nm());n.put("emp_se_cd",row.cand_se_cd());n.put("main_job_cd",row.job_cd());
        if(row.vndr_ptnr_id()==null)n.putNull("vndr_ptnr_id");else n.put("vndr_ptnr_id",row.vndr_ptnr_id());
        if(row.grd_cd()==null)n.putNull("grd_cd");else n.put("grd_cd",row.grd_cd());
        n.putArray("skill");for(String skill:mapper.skills(id))n.withArray("skill").add(skill);
        if(row.tel_no()!=null)n.put("tel_no",row.tel_no());
        var developer=developers.create(DeveloperInput.parse(n,false));
        var conversion=new Conversion(id,developer.item().dev_id(),user.userId());
        mapper.linkDeveloper(conversion);mapper.convert(conversion);
        mapper.audit(new CandidateAudit(Long.toString(id),"UPDATE","dev_id",null,Long.toString(conversion.developerId()),user.userId()));
        return new CandidateConversionResult(get(id),developer);
    }
}
