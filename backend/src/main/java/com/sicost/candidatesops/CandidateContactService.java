package com.sicost.candidatesops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.developers.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.candidatesops.CandidateContactModels.*;

@Service
@Transactional(readOnly=true)
public class CandidateContactService {
    private final CandidateContactMapper mapper;
    private final DeveloperMapper codes;
    private final DeveloperService developers;
    private final CurrentUser user;
    private final Clock clock;
    public CandidateContactService(CandidateContactMapper mapper, DeveloperMapper codes,
            DeveloperService developers, CurrentUser user, Clock clock) {
        this.mapper=mapper;this.codes=codes;this.developers=developers;this.user=user;this.clock=clock;
    }
    private CandidateRow locked(long id) {
        var row=mapper.lock(CandidateContactInput.id(id));
        if(row==null)throw ApiException.notFound("후보가 없습니다.");return row;
    }
    public CandidateDetail detail(long id) {
        var row=mapper.row(id);if(row==null)throw ApiException.notFound("후보가 없습니다.");
        var matching=mapper.matching(id).stream().map(s->new MatchingSlot(s.need_id(),s.oppt_id(),s.oppt_nm(),
            s.job_cd(),s.bgn_ym(),s.monthly_price()==null?0:s.monthly_price(),
            s.hope_untprc_amt()==null?0:s.hope_untprc_amt(),0,null,true,List.of("원가 미확인"))).toList();
        return new CandidateDetail(row,mapper.skills(id),mapper.contacts(id),matching);
    }
    @Transactional
    public CandidateDetail contact(long id, JsonNode body) {
        var input=CandidateContactInput.contact(body,clock);
        var before=locked(id);
        if(!codes.activeCode(new DeveloperModels.DeveloperCode("CONTACT_STATUS",input.cntct_stts_cd())))throw CandidateContactInput.invalid("cntct_stts_cd");
        var write=new ContactWrite(id,input,user.userId());
        mapper.append(write);mapper.refreshContact(write);
        var result=detail(id);
        audit(id,"cntct_stts_cd",before.cntct_stts_cd(),result.row().cntct_stts_cd());
        audit(id,"last_cntct_dt",before.last_cntct_dt(),result.row().last_cntct_dt());
        return result;
    }
    @Transactional
    public ConversionResult convert(long id, JsonNode body) {
        CandidateContactInput.fields(body,Set.of("developer"));
        var before=locked(id);
        if(before.dev_id()!=null)throw alreadyConverted();
        var supplied=body.get("developer");
        if(supplied==null || !supplied.isObject())throw CandidateContactInput.invalid("developer");
        // Required supplemental input is validated before candidate-owned fields override conflicts.
        if(!supplied.path("emp_se_cd").isTextual() || !Set.of("FT","CT","FL","OS").contains(supplied.path("emp_se_cd").asText()))throw CandidateContactInput.invalid("emp_se_cd");
        ObjectNode merged=((ObjectNode)supplied).deepCopy();
        merged.put("dev_nm",before.cand_nm());merged.put("emp_se_cd",before.cand_se_cd());
        merged.put("main_job_cd",before.job_cd());
        if(before.grd_cd()==null)merged.putNull("grd_cd");else merged.put("grd_cd",before.grd_cd());
        if(before.vndr_ptnr_id()==null)merged.putNull("vndr_ptnr_id");else merged.put("vndr_ptnr_id",before.vndr_ptnr_id());
        var skills=merged.putArray("skill");mapper.skills(id).forEach(skills::add);
        var developer=developers.create(DeveloperInput.parse(merged,false));
        long developerId=developer.item().dev_id();
        if(mapper.convert(new ConversionWrite(id,developerId,user.userId()))!=1)throw alreadyConverted();
        audit(id,"dev_id",null,developerId);audit(id,"cntct_stts_cd",before.cntct_stts_cd(),"SIGNED");
        return new ConversionResult(detail(id),developer);
    }
    private ApiException alreadyConverted(){return ApiException.conflict("ALREADY_CONVERTED","이미 개발자로 등록된 후보입니다.","dev_id");}
    private void audit(long id,String field,Object before,Object after) {
        if(!Objects.equals(before,after))mapper.audit(new CandidateAudit(Long.toString(id),field,
            before==null?null:before.toString(),after==null?null:after.toString(),user.userId()));
    }
}
