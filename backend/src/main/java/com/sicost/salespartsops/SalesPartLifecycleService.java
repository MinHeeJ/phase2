package com.sicost.salespartsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.salesparts.*;
import com.sicost.salesparts.SalesPartModels.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.salespartsops.SalesPartLifecycleModels.*;

@Service
@Transactional(readOnly=true)
public class SalesPartLifecycleService {
    private final SalesPartLifecycleMapper mapper;
    private final SalesPartMapper reads;
    private final SalesPartService parts;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public SalesPartLifecycleService(SalesPartLifecycleMapper mapper,SalesPartMapper reads,SalesPartService parts,
                                     CurrentUser user,PermissionService permissions,Clock clock) {
        this.mapper=mapper;this.reads=reads;this.parts=parts;this.user=user;this.permissions=permissions;this.clock=clock;
    }
    private LocalDate today() {return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    private PartRow lock(long id) {
        PartRow part=mapper.lock(id,today());
        if(part==null) throw ApiException.notFound("영업 파트가 없습니다.");
        return part;
    }
    private void headquarters(PartRow part) {
        if("Y".equals(part.hq_yn())) throw ApiException.conflict("HQ_PART_LOCKED","본사 파트는 변경할 수 없습니다.",null);
    }
    public List<SalesPartOption> options() {permissions.can("UI-15.read");return mapper.options();}
    private void audit(String id,String type,String column,String before,String after) {
        mapper.audit(new SalesPartAudit("mst.bsn_part",id,type,column,before,after,user.userId()));
    }
    @Transactional
    public SalesPartDetail rename(String key,String name) {
        permissions.can("UI-15.update");long id=SalesPartLifecycleInput.id(key);var part=lock(id);headquarters(part);
        if(part.bsn_part_nm().equals(name)) return parts.get(key);
        if(mapper.nameExists(id,name)) throw duplicate();
        try {mapper.rename(new PartValueWrite(id,name,user.userId()));}
        catch(DuplicateKeyException e) {throw duplicate();}
        audit(key,"UPDATE","bsn_part_nm",part.bsn_part_nm(),name);
        return parts.get(key);
    }
    private ApiException duplicate() {return ApiException.conflict("DUPLICATE_PART_NAME","같은 이름의 파트가 이미 있습니다.","bsn_part_nm");}
    @Transactional
    public SalesPartDetail disable(String key) {
        permissions.can("UI-15.delete");long id=SalesPartLifecycleInput.id(key);var part=lock(id);headquarters(part);
        if("N".equals(part.use_yn())) return parts.get(key);
        if(part.peopleCount()>0 || part.projectCount()>0) throw ApiException.conflict("PART_IN_USE",
            part.bsn_part_nm()+"에 소속 인력 "+part.peopleCount()+"명, 진행 프로젝트 "+part.projectCount()+"건이 있습니다. 먼저 다른 파트로 옮겨 주세요.",null);
        mapper.state(new PartValueWrite(id,"N",user.userId()));audit(key,"DISABLE","use_yn","Y","N");
        return parts.get(key);
    }
    @Transactional
    public SalesPartDetail enable(String key) {
        permissions.can("UI-15.delete");long id=SalesPartLifecycleInput.id(key);var part=lock(id);
        if("N".equals(part.use_yn())) {
            mapper.state(new PartValueWrite(id,"Y",user.userId()));audit(key,"UPDATE","use_yn","N","Y");
        }
        return parts.get(key);
    }
    @Transactional
    public SalesPartDetail standard(String key,StandardSave input) {
        permissions.can("UI-15.update");long id=SalesPartLifecycleInput.id(key);headquarters(lock(id));
        String closed=reads.lastClosed(),month=input.aply_bgn_ym(),now=YearMonth.from(today()).toString();
        if(closed!=null && month.compareTo(closed)<=0) throw ApiException.conflict("CLOSED_MONTH","적용 시작월은 마감된 달("+closed+") 다음 달부터 고를 수 있습니다.","aply_bgn_ym");
        var standards=reads.standards(id,now);
        var effective=standards.stream().filter(s->s.aply_bgn_ym().compareTo(month)<=0 && (s.aply_end_ym()==null || s.aply_end_ym().compareTo(month)>=0)).findFirst().orElse(null);
        if(effective!=null && Objects.equals(effective.ldr_nm(),input.ldr_nm()) && effective.prft_dstrb_rt().compareTo(input.prft_dstrb_rt())==0 && effective.bsn_fee_amt()==input.bsn_fee_amt())
            throw ApiException.conflict("SAME_STANDARD","지금 기준과 같습니다. 바꿀 값을 입력해 주세요.",null);
        var replaced=standards.stream().filter(s->s.aply_bgn_ym().compareTo(month)>=0).toList();
        if(replaced.stream().anyMatch(s->s.aply_bgn_ym().compareTo(now)<=0)) throw started();
        var write=new StandardReplacement(id,input,YearMonth.parse(month).minusMonths(1).toString(),user.userId());
        try {mapper.deleteScheduled(write);}
        catch(DataIntegrityViolationException e) {
            if(e.getMostSpecificCause() instanceof java.sql.SQLException sql && "23503".equals(sql.getSQLState())) throw started();
            throw e;
        }
        for(var old:replaced) mapper.audit(new SalesPartAudit("mst.bsn_part_std_hist",old.part_std_hist_id(),"DELETE",null,
            summary(old.aply_bgn_ym(),old.aply_end_ym(),old.ldr_nm(),old.prft_dstrb_rt().toPlainString(),old.bsn_fee_amt()),null,user.userId()));
        mapper.closePrevious(write);
        Long inserted=mapper.insertStandard(write);
        mapper.audit(new SalesPartAudit("mst.bsn_part_std_hist",inserted.toString(),"INSERT",null,null,
            summary(month,null,input.ldr_nm(),input.prft_dstrb_rt().toPlainString(),input.bsn_fee_amt()),user.userId()));
        return parts.get(key);
    }
    private ApiException started() {return ApiException.badRequest("적용 중이거나 지난 기준은 대체할 수 없습니다.","aply_bgn_ym");}
    private String summary(String begin,String end,String leader,String rate,long fee) {
        return "적용 시작월: "+begin+", 종료월: "+end+", 파트장: "+leader+", 배분율: "+rate+", 영업비: "+fee;
    }
}
