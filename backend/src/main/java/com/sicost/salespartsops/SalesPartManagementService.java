package com.sicost.salespartsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.salespartsops.SalesPartModels.*;

@Service
@Transactional(readOnly=true)
public class SalesPartManagementService {
    private final SalesPartManagementMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ObjectMapper json;
    public SalesPartManagementService(SalesPartManagementMapper mapper, CurrentUser user, PermissionService permissions, Clock clock, ObjectMapper json) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;this.json=json;
    }
    private Read query(long id) {
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        return new Read(id,today,YearMonth.from(today).toString());
    }
    private Part requirePart(Read q, boolean lock) {
        Part p=lock?mapper.lock(q):mapper.part(q);
        if(p==null) throw ApiException.notFound("영업 파트가 없습니다.");
        return p;
    }
    private void editable(Part p) {
        if("Y".equals(p.hq_yn())) throw ApiException.conflict("HQ_PART_LOCKED","본사 파트는 변경할 수 없습니다.",null);
    }
    private JsonNode body(String raw) {
        try { return json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw); }
        catch(JsonProcessingException ex) { throw ApiException.badRequest("입력값을 확인하세요.",null); }
    }
    private Detail detail(Read q) {
        Part p=requirePart(q,false);
        List<Standard> standards=mapper.standards(q);
        Standard current=standards.stream().filter(s->s.aply_bgn_ym().compareTo(q.month())<=0 && (s.aply_end_ym()==null || s.aply_end_ym().compareTo(q.month())>=0)).reduce((a,b)->b).orElse(null);
        String closed=mapper.lastClosed();
        return new Detail(p.bsn_part_id(),p.bsn_part_nm(),p.hq_yn(),p.use_yn(),p.sort_seq(),q.month(),current,
            standards.stream().filter(s->s.aply_bgn_ym().compareTo(q.month())>0).toList(),p.peopleCount(),p.projectCount(),standards,closed,
            closed==null?null:YearMonth.parse(closed).plusMonths(1).toString());
    }
    public List<Option> options() { permissions.can("UI-15.read");return mapper.options(); }
    @Transactional
    public Detail rename(String id, String raw) {
        permissions.can("UI-15.update");Read q=query(SalesPartInput.id(id));Part p=requirePart(q,true);editable(p);
        String name=SalesPartInput.name(body(raw));
        if(name.equals(p.bsn_part_nm())) return detail(q);
        ValueWrite write=new ValueWrite(q.id(),name,user.userId());
        if(mapper.nameExists(write)) throw duplicate();
        try { mapper.rename(write); } catch(DuplicateKeyException ex) { throw duplicate(); }
        mapper.appendHistory(new History("mst.bsn_part",p.bsn_part_id(),"UPDATE","bsn_part_nm",p.bsn_part_nm(),name,user.userId()));
        return detail(q);
    }
    @Transactional
    public Detail disable(String id) {
        permissions.can("UI-15.delete");Read q=query(SalesPartInput.id(id));Part p=requirePart(q,true);editable(p);
        if("N".equals(p.use_yn())) return detail(q);
        if(p.peopleCount()>0 || p.projectCount()>0) throw ApiException.conflict("PART_IN_USE",p.bsn_part_nm()+"에 소속 인력 "+p.peopleCount()+"명, 진행 프로젝트 "+p.projectCount()+"건이 있습니다. 먼저 다른 파트로 옮겨 주세요.",null);
        changeUse(p,q,"N","DISABLE");return detail(q);
    }
    @Transactional
    public Detail enable(String id) {
        permissions.can("UI-15.delete");Read q=query(SalesPartInput.id(id));Part p=requirePart(q,true);
        if("Y".equals(p.use_yn())) return detail(q);
        changeUse(p,q,"Y","UPDATE");return detail(q);
    }
    private void changeUse(Part p, Read q, String use, String type) {
        mapper.use(new ValueWrite(q.id(),use,user.userId()));
        mapper.appendHistory(new History("mst.bsn_part",p.bsn_part_id(),type,"use_yn",p.use_yn(),use,user.userId()));
    }
    @Transactional
    public Detail saveStandard(String id, String raw) {
        permissions.can("UI-15.update");Read q=query(SalesPartInput.id(id));Part p=requirePart(q,true);editable(p);
        StandardSave input=SalesPartInput.standard(body(raw));String start=input.aply_bgn_ym(),closed=mapper.lastClosed();
        if(closed!=null && start.compareTo(closed)<=0) throw ApiException.conflict("CLOSED_MONTH","적용 시작월은 마감된 달("+closed+") 다음 달부터 고를 수 있습니다.","aply_bgn_ym");
        List<Standard> rows=mapper.standards(q);
        Standard effective=rows.stream().filter(s->s.aply_bgn_ym().compareTo(start)<=0 && (s.aply_end_ym()==null || s.aply_end_ym().compareTo(start)>=0)).reduce((a,b)->b).orElse(null);
        if(effective!=null && Objects.equals(effective.ldr_nm(),input.ldr_nm()) && effective.prft_dstrb_rt().compareTo(input.prft_dstrb_rt())==0 && effective.bsn_fee_amt()==input.bsn_fee_amt()) throw same();
        if(rows.stream().anyMatch(s->s.aply_bgn_ym().compareTo(start)>=0 && s.aply_bgn_ym().compareTo(q.month())<=0)) throw same();
        PeriodWrite period=new PeriodWrite(q.id(),start,YearMonth.parse(start).minusMonths(1).toString(),q.month());
        List<Standard> deleted=rows.stream().filter(s->s.aply_bgn_ym().compareTo(start)>=0 && s.aply_bgn_ym().compareTo(q.month())>0).toList();
        mapper.deleteScheduled(period);
        for(Standard s:deleted) mapper.appendHistory(new History("mst.bsn_part_std_hist",s.part_std_hist_id(),"DELETE",null,summary(q.id(),new StandardSave(s.aply_bgn_ym(),s.ldr_nm(),s.prft_dstrb_rt(),s.bsn_fee_amt())),null,user.userId()));
        mapper.closePrevious(period);
        Long newId=mapper.insertStandard(new StandardWrite(q.id(),input,user.userId()));
        mapper.appendHistory(new History("mst.bsn_part_std_hist",newId.toString(),"INSERT",null,null,summary(q.id(),input),user.userId()));
        return detail(q);
    }
    private String summary(long id, StandardSave input) {
        try { var value=new LinkedHashMap<String,Object>();value.put("bsn_part_id",Long.toString(id));value.put("aply_bgn_ym",input.aply_bgn_ym());value.put("ldr_nm",input.ldr_nm());value.put("prft_dstrb_rt",input.prft_dstrb_rt());value.put("bsn_fee_amt",input.bsn_fee_amt());return json.writeValueAsString(value); }
        catch(JsonProcessingException ex) { throw new IllegalStateException("기준 이력을 기록할 수 없습니다.",ex); }
    }
    private static ApiException duplicate() { return ApiException.conflict("DUPLICATE_PART_NAME","같은 이름의 파트가 이미 있습니다.","bsn_part_nm"); }
    private static ApiException same() { return ApiException.conflict("SAME_STANDARD","지금 기준과 같습니다. 바꿀 값을 입력해 주세요.",null); }
}
