package com.sicost.salespartsops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.security.PermissionService;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.salespartsops.SalesPartActionModels.*;

@Service
@Transactional(readOnly=true)
public class SalesPartActionService {
    private final SalesPartActionMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ObjectMapper json;
    public SalesPartActionService(SalesPartActionMapper mapper,CurrentUser user,PermissionService permissions,Clock clock,ObjectMapper json) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;this.json=json;
    }
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    private Read read(String id) { return new Read(id,YearMonth.from(today()).toString(),today()); }
    private Part require(String id,boolean lock) {
        if(id==null || !id.matches("[1-9][0-9]{0,18}") || new java.math.BigInteger(id).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))>0) throw ApiException.notFound("파트가 없습니다.");
        Part part=lock?mapper.lockPart(id):mapper.part(id);
        if(part==null) throw ApiException.notFound("파트가 없습니다.");return part;
    }
    private void editable(Part part) {
        if("Y".equals(part.hq_yn())) throw ApiException.conflict("HQ_PART_LOCKED","본사 파트는 변경할 수 없습니다.",null);
    }
    public List<Option> options() { permissions.can("UI-15.read");return mapper.options(); }
    private Detail detail(Part part) {
        Read q=read(part.bsn_part_id());List<Standard> standards=mapper.standards(q);
        String closed=mapper.lastClosed();
        return new Detail(part.bsn_part_id(),part.bsn_part_nm(),part.hq_yn(),part.use_yn(),part.sort_seq(),q.month(),effective(standards,q.month()),
            standards.stream().filter(s->s.aply_bgn_ym().compareTo(q.month())>0).toList(),mapper.people(q),mapper.projects(q.id()),standards,closed,
            closed==null?q.month():YearMonth.parse(closed).plusMonths(1).toString());
    }
    private Standard effective(List<Standard> standards,String month) {
        return standards.stream().filter(s->s.aply_bgn_ym().compareTo(month)<=0 && (s.aply_end_ym()==null || s.aply_end_ym().compareTo(month)>=0)).findFirst().orElse(null);
    }
    private void audit(String table,String id,String type,String field,String before,String after) {
        mapper.history(new History(table,id,type,field,before,after,user.userId()));
    }
    @Transactional
    public Detail rename(String id,JsonNode input) {
        permissions.can("UI-15.update");Part part=require(id,true);editable(part);String name=SalesPartActionInput.name(input);
        if(name.equals(part.bsn_part_nm())) return detail(part);
        PartWrite write=new PartWrite(id,name,user.userId());
        if(mapper.nameExists(write)) throw duplicate();
        try { mapper.rename(write); } catch(DuplicateKeyException e) { throw duplicate(); }
        audit("mst.bsn_part",id,"UPDATE","bsn_part_nm",part.bsn_part_nm(),name);
        return detail(require(id,false));
    }
    private ApiException duplicate() { return ApiException.conflict("DUPLICATE_PART_NAME","같은 이름의 파트가 이미 있습니다.","bsn_part_nm"); }
    @Transactional
    public Detail disable(String id) {
        permissions.can("UI-15.delete");Part part=require(id,true);editable(part);
        if("N".equals(part.use_yn())) return detail(part);
        long people=mapper.people(read(id)),projects=mapper.projects(id);
        if(people>0 || projects>0) throw ApiException.conflict("PART_IN_USE",part.bsn_part_nm()+"에 소속 인력 "+people+"명, 진행 프로젝트 "+projects+"건이 있습니다. 먼저 다른 파트로 옮겨 주세요.",null);
        mapper.state(new PartWrite(id,"N",user.userId()));audit("mst.bsn_part",id,"DISABLE","use_yn","Y","N");
        return detail(require(id,false));
    }
    @Transactional
    public Detail enable(String id) {
        permissions.can("UI-15.delete");Part part=require(id,true);
        if("Y".equals(part.use_yn())) return detail(part);
        mapper.state(new PartWrite(id,"Y",user.userId()));audit("mst.bsn_part",id,"UPDATE","use_yn","N","Y");
        return detail(require(id,false));
    }
    @Transactional
    public Detail saveStandard(String id,JsonNode input) {
        permissions.can("UI-15.update");Part part=require(id,true);editable(part);
        StandardSave next=SalesPartActionInput.standard(input);String month=next.aply_bgn_ym();
        String closed=mapper.lastClosed(),now=read(id).month();
        String first=closed==null?now:YearMonth.parse(closed).plusMonths(1).toString();
        if(month.compareTo(first)<0) throw closed(closed);
        List<Standard> standards=mapper.standards(read(id));
        List<Standard> replacing=standards.stream().filter(s->s.aply_bgn_ym().compareTo(month)>=0).toList();
        for(Standard s:replacing) if(s.aply_bgn_ym().compareTo(now)<=0 || mapper.standardReferences(s.part_std_hist_id())>0) throw closed(closed);
        Standard current=effective(standards,month);
        if(current!=null && Objects.equals(current.ldr_nm(),next.ldr_nm()) && current.prft_dstrb_rt().compareTo(next.prft_dstrb_rt())==0 && Objects.equals(current.bsn_fee_amt(),next.bsn_fee_amt()))
            throw ApiException.conflict("SAME_STANDARD","지금 기준과 같습니다. 바꿀 값을 입력해 주세요.",null);
        for(Standard s:replacing) {
            mapper.deleteStandard(s.part_std_hist_id());audit("mst.bsn_part_std_hist",s.part_std_hist_id(),"DELETE",null,summary(s),null);
        }
        String end=YearMonth.parse(month).minusMonths(1).toString();
        for(Standard s:standards) if(s.aply_bgn_ym().compareTo(month)<0 && (s.aply_end_ym()==null || s.aply_end_ym().compareTo(month)>=0)) {
            mapper.closeStandard(new EndWrite(s.part_std_hist_id(),end));
            audit("mst.bsn_part_std_hist",s.part_std_hist_id(),"UPDATE","aply_end_ym",s.aply_end_ym(),end);
        }
        String newId=mapper.insertStandard(new StandardWrite(id,next,user.userId()));
        audit("mst.bsn_part_std_hist",newId,"INSERT",null,null,encode(next));
        return detail(require(id,false));
    }
    private ApiException closed(String last) {
        return ApiException.conflict("CLOSED_MONTH",last==null?"이미 시작된 기준은 다시 작성할 수 없습니다.":"적용 시작월은 마감된 달("+last+") 다음 달부터 고를 수 있습니다. 이미 시작된 기준은 다시 작성할 수 없습니다.","aply_bgn_ym");
    }
    private String summary(Standard s) { return encode(new StandardSave(s.aply_bgn_ym(),s.ldr_nm(),s.prft_dstrb_rt(),s.bsn_fee_amt())); }
    private String encode(Object value) {
        try {return json.writeValueAsString(value);} catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("기록을 생성할 수 없습니다.",e);}
    }
    @Transactional
    public File export(List<String> columns,List<String> ids) {
        permissions.can("UI-15.download");SalesPartWorkbook.validateColumns(columns);
        List<Part> parts=mapper.allParts();
        if(ids!=null) {
            if(ids.isEmpty() || ids.stream().anyMatch(i->i==null || !i.matches("[1-9][0-9]{0,18}"))) throw ApiException.badRequest("선택한 파트를 확인하세요.","ids");
            Set<String> wanted=new HashSet<>(ids),existing=new HashSet<>();parts.forEach(p->existing.add(p.bsn_part_id()));
            if(!existing.containsAll(wanted)) throw ApiException.badRequest("선택한 파트를 확인하세요.","ids");
            parts=parts.stream().filter(p->wanted.contains(p.bsn_part_id())).toList();
        }
        List<Detail> rows=parts.stream().map(this::detail).toList();
        String filename="영업파트_"+today().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)+".xlsx";
        byte[] bytes=SalesPartWorkbook.write(rows,columns,user.name(),LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        Map<String,Object> details=new LinkedHashMap<>();details.put("columns",columns);details.put("ids",ids);details.put("rowCount",rows.size());details.put("filters",Map.of());
        mapper.download(new Download(filename,encode(details),user.userId()));return new File(filename,bytes);
    }
}
