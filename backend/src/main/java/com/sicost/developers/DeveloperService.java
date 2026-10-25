package com.sicost.developers;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.validation.DeveloperManagementNumbers;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.developers.DeveloperModels.*;

@Service
@Transactional(readOnly=true)
public class DeveloperService {
    private final DeveloperMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public DeveloperService(DeveloperMapper mapper,CurrentUser user,PermissionService permissions,Clock clock) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;
    }
    private LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    public PageResult<DeveloperListItem> list(DeveloperPaging page,DeveloperQuery.Filters filters) {
        permissions.can("UI-02.read");var query=DeveloperQuery.of(page,filters,today());
        return page.result(mapper.list(query),mapper.count(query));
    }
    private DeveloperRow require(long id) {
        DeveloperInput.id(id);var row=mapper.row(id);
        if(row==null)throw ApiException.notFound("개발자가 없습니다.");return row;
    }
    public DeveloperDetail get(long id) {
        permissions.can("UI-02.read");var row=require(id);var date=today();var month=YearMonth.from(date);
        Map<String,String> names=new LinkedHashMap<>();
        for(var code:mapper.codeNames())names.put(code.group()+":"+code.code(),code.name());
        return new DeveloperDetail(mapper.summary(new DeveloperRead(id,date,month.toString(),month.plusMonths(2).toString())),row.vndr_ptnr_id(),row.pstn_cd(),row.duty_se_cd(),
            mapper.subJobs(id),mapper.skills(id),row.bsn_part_id(),row.hire_dt(),row.tel_no(),row.email_addr(),row.rmrk_desc(),mapper.latestSalary(id),row.updated_at(),names,row.bsn_part_id()==null?null:mapper.partName(row.bsn_part_id()));
    }
    private void code(String group,String value,String old,String field) {
        if(value!=null && !Objects.equals(value,old) && !mapper.activeCode(new DeveloperCode(group,value)))throw ApiException.badRequest("사용 중인 코드를 골라 주세요.",field);
    }
    private void choices(DeveloperSave input,DeveloperSave before) {
        code("EMP_TYPE",input.emp_se_cd(),before==null?null:before.emp_se_cd(),"emp_se_cd");
        code("JOB",input.main_job_cd(),before==null?null:before.main_job_cd(),"main_job_cd");
        code("POSITION",input.pstn_cd(),before==null?null:before.pstn_cd(),"pstn_cd");
        code("WORK_TYPE",input.duty_se_cd(),before==null?null:before.duty_se_cd(),"duty_se_cd");
        code("GRADE",input.grd_cd(),before==null?null:before.grd_cd(),"grd_cd");
        for(String job:input.sub_job())code("JOB",job,before!=null&&before.sub_job().contains(job)?job:null,"sub_job");
        for(String skill:input.skill())code("SKILL",skill,before!=null&&before.skill().contains(skill)?skill:null,"skill");
        if(input.emp_se_cd().equals("OS") && (input.vndr_ptnr_id()==null || !mapper.activePartner(input.vndr_ptnr_id())))throw ApiException.badRequest("외주는 협력사를 골라 주세요.","vndr_ptnr_id");
        if(input.bsn_part_id()!=null && !mapper.activePart(input.bsn_part_id()))throw ApiException.badRequest("사용 중인 영업 파트를 골라 주세요.","bsn_part_id");
    }
    @Transactional
    public DeveloperDetail create(DeveloperSave input) {
        permissions.can("UI-02.create");choices(input,null);
        long id=mapper.nextId();String no=input.mng_no()==null?DeveloperManagementNumbers.allocated(id):input.mng_no();
        if(mapper.duplicateNumber(no))throw DeveloperManagementNumbers.duplicate();
        mapper.insert(new DeveloperWrite(id,no,input,Set.of(),user.userId()));
        for(String job:input.sub_job())mapper.insertJob(new DeveloperChild(id,job));
        for(String skill:input.skill())mapper.insertSkill(new DeveloperChild(id,skill));
        mapper.appendHistory(new DeveloperHistory(Long.toString(id),"INSERT",null,null,null,user.userId()));
        return get(id);
    }
    @Transactional
    public DeveloperSaveResult update(long id,DeveloperSave input) {
        permissions.can("UI-02.update");var row=require(id);
        var before=row.input(mapper.subJobs(id),mapper.skills(id));choices(input,before);
        Map<String,String> old=values(before),after=values(input);Set<String> changed=new LinkedHashSet<>();
        old.forEach((key,value)->{if(!Objects.equals(value,after.get(key)))changed.add(key);});
        boolean jobs=!new TreeSet<>(before.sub_job()).equals(new TreeSet<>(input.sub_job()));
        boolean skills=!new TreeSet<>(before.skill()).equals(new TreeSet<>(input.skill()));
        if(changed.isEmpty()&&!jobs&&!skills)return new DeveloperSaveResult(get(id),false);
        if(!changed.isEmpty())mapper.update(new DeveloperWrite(id,row.mng_no(),input,changed,user.userId()));
        for(String field:changed)audit(id,field,old.get(field),after.get(field));
        if(jobs) {
            mapper.deleteJobs(id);for(String job:input.sub_job())mapper.insertJob(new DeveloperChild(id,job));
            audit(id,"sub_job",names("JOB",before.sub_job()),names("JOB",input.sub_job()));
        }
        if(skills) {
            mapper.deleteSkills(id);for(String skill:input.skill())mapper.insertSkill(new DeveloperChild(id,skill));
            audit(id,"skill",names("SKILL",before.skill()),names("SKILL",input.skill()));
        }
        return new DeveloperSaveResult(get(id),true);
    }
    private String names(String group,List<String> codes) {
        Map<String,String> labels=new HashMap<>();for(var c:mapper.codeNames())if(c.group().equals(group))labels.put(c.code(),c.name());
        return codes.stream().sorted().map(c->labels.getOrDefault(c,c)).collect(java.util.stream.Collectors.joining(","));
    }
    private void audit(long id,String field,String before,String after) {mapper.appendHistory(new DeveloperHistory(Long.toString(id),"UPDATE",field,before,after,user.userId()));}
    private static String value(Object value){return value==null?null:value.toString();}
    private static Map<String,String> values(DeveloperSave r) {
        Map<String,String> v=new LinkedHashMap<>();
        v.put("dev_nm",r.dev_nm());v.put("emp_se_cd",r.emp_se_cd());v.put("main_job_cd",r.main_job_cd());v.put("vndr_ptnr_id",value(r.vndr_ptnr_id()));
        v.put("pstn_cd",r.pstn_cd());v.put("duty_se_cd",r.duty_se_cd());v.put("grd_cd",r.grd_cd());v.put("bsn_part_id",value(r.bsn_part_id()));
        v.put("hire_dt",value(r.hire_dt()));v.put("tel_no",r.tel_no());v.put("email_addr",r.email_addr());v.put("rmrk_desc",r.rmrk_desc());v.put("dflt_untprc_amt",value(r.dflt_untprc_amt()));return v;
    }
}
