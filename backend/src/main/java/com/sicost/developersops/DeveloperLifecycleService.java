package com.sicost.developersops;

import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.developersops.DeveloperLifecycleModels.*;

@Service
@Transactional(readOnly=true)
public class DeveloperLifecycleService {
    private final DeveloperLifecycleMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ExcelExportService excel;
    public DeveloperLifecycleService(DeveloperLifecycleMapper mapper, CurrentUser user, PermissionService permissions, Clock clock, ExcelExportService excel) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;this.excel=excel;
    }
    private LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    static long validId(long id,String field){if(id<=0)throw ApiException.badRequest("식별자를 확인하세요.",field);return id;}
    private DeveloperState require(long id){validId(id,"id");var row=mapper.state(id);if(row==null)throw ApiException.notFound("개발자가 없습니다.");return row;}
    static String text(String value,String field,int limit){if(value==null)return null;value=value.trim();if(value.length()>limit)throw ApiException.badRequest("입력값을 확인하세요.",field);return value.isEmpty()?null:value;}
    static String choice(String value,String field,Set<String> allowed){value=text(value,field,50);if(value!=null&&!allowed.contains(value))throw ApiException.badRequest("입력값을 확인하세요.",field);return value;}
    static String order(String sort, String fallback, Set<String> columns){
        if(sort==null||sort.isBlank())sort=fallback;
        String[] parts=sort.split(",",-1);
        if(parts.length!=2||!columns.contains(parts[0])||!Set.of("asc","desc").contains(parts[1]))throw ApiException.badRequest("정렬을 확인하세요.","sort");
        return "\""+parts[0]+"\" "+parts[1];
    }
    public List<DeveloperOption> options(String q){permissions.can("UI-02.read");return mapper.options(new DeveloperOptionQuery(text(q,"q",200),today()));}
    private DeveloperReadQuery query(long id, DeveloperPaging page, String filter, String order, String from, String to){
        require(id);permissions.can("UI-02.read");return new DeveloperReadQuery(id,filter,from,to,order,page.size(),page.toPageRequest().offset());
    }
    public PageResult<DeveloperAssignment> assignments(long id, DeveloperPaging page, String status){
        status=choice(status,"status",Set.of("ACTIVE","ENDED","REPLACED","CANCELLED"));
        var q=query(id,page,status,order(page.sort(),"bgn_dt,desc",Set.of("prj_nm","job_cd","job_name","bgn_dt","end_dt","asgn_rt","asgn_stts_cd")),null,null);
        return page.result(mapper.assignments(q),mapper.assignmentCount(q));
    }
    public PageResult<DeveloperChange> history(long id, DeveloperPaging page, String item){
        item=text(item,"item",50);if(item!=null&&!item.matches("[a-z_]{1,50}"))throw ApiException.badRequest("변경 항목을 확인하세요.","item");
        var q=query(id,page,item,order(page.sort(),"changed_at,desc",Set.of("changed_at","item","before","after","actor","change_type")),null,null);
        return page.result(mapper.history(q),mapper.historyCount(q));
    }
    private String month(String value,String field){if(value==null)return null;if(!value.matches("[0-9]{4}-(0[1-9]|1[0-2])"))throw ApiException.badRequest("연월을 확인하세요.",field);return value;}
    public PageResult<DeveloperMonthlyCost> monthlyCosts(long id, DeveloperPaging page, String from, String to){
        from=month(from,"from");to=month(to,"to");if(from!=null&&to!=null&&from.compareTo(to)>0)throw ApiException.badRequest("기간을 확인하세요.","to");
        var now=YearMonth.from(today());String lower=now.minusMonths(11).toString(),upper=now.toString();
        if(from!=null&&from.compareTo(lower)>0)lower=from;if(to!=null&&to.compareTo(upper)<0)upper=to;
        var q=query(id,page,null,order(page.sort(),"cst_ym,desc",Set.of("cst_ym","emp_se_cd","slry_amt","alwnc_amt","sins_cmpny_amt","rtrpay_acml_amt","indv_lbr_cst_amt","real_pay_amt")),lower,upper);
        return page.result(mapper.monthlyCosts(q),mapper.monthlyCostCount(q));
    }
    @Transactional
    public DeveloperDisableResult disable(List<Long> input){
        permissions.can("UI-02.delete");if(input==null||input.isEmpty())throw ApiException.badRequest("개발자를 선택하세요.","ids");
        for(Long id:input){if(id==null)throw ApiException.badRequest("식별자를 확인하세요.","ids");validId(id,"ids");}
        var ids=new LinkedHashSet<>(input);var rows=new LinkedHashMap<Long,DeveloperState>();
        for(long id:ids)rows.put(id,require(id));
        var blocked=new ArrayList<DeveloperBlock>();var allowed=new ArrayList<Long>();
        for(var row:rows.values()){
            if("N".equals(row.use_yn()))continue;
            long count=mapper.currentAssignments(new DeveloperDate(row.dev_id(),today()));
            if(count>0)blocked.add(new DeveloperBlock(row.dev_id(),row.dev_nm(),count,"DEVELOPER_IN_USE","현재 투입 "+count+"건"));else allowed.add(row.dev_id());
        }
        if(ids.size()==1&&!blocked.isEmpty())throw ApiException.conflict("DEVELOPER_IN_USE",blocked.get(0).reason(),null);
        for(long id:allowed){mapper.changeState(new DeveloperStateWrite(id,"N",user.userId()));mapper.audit(new DeveloperAudit(id,"DISABLE","Y","N",user.userId()));}
        return new DeveloperDisableResult(List.copyOf(allowed),List.copyOf(blocked),allowed.size(),blocked.size());
    }
    @Transactional
    public Map<String,Object> enable(long id){
        permissions.can("UI-02.delete");var before=require(id);
        if("N".equals(before.use_yn())){mapper.changeState(new DeveloperStateWrite(id,"Y",user.userId()));mapper.audit(new DeveloperAudit(id,"UPDATE","N","Y",user.userId()));}
        var result=new LinkedHashMap<>(mapper.detail(new DeveloperDate(id,today())));
        for(String key:List.of("rsgn_dt","latest_assignment_end_dt","dflt_untprc_amt","grd_cd","grade_name","vndr_ptnr_id","pstn_cd","bsn_part_id","hire_dt","tel_no","email_addr","rmrk_desc","updated_at","current_project_name"))result.putIfAbsent(key,null);
        result.replaceAll((key,value)->value instanceof java.sql.Date date?date.toLocalDate():value instanceof java.sql.Timestamp timestamp?timestamp.toLocalDateTime():value);
        result.put("sub_job",mapper.subJobs(id));result.put("skill",mapper.skills(id));result.put("latest_salary",mapper.latestSalary(id));
        return result;
    }
    public DeveloperFile export(DeveloperExportQuery input){
        permissions.can("UI-02.download");
        var q=new DeveloperExportQuery(choice(input.empType(),"empType",Set.of("FT","CT","FL","OS")),text(input.job(),"job",30),choice(input.status(),"status",Set.of("퇴사","휴직","철수예정","투입중","대기")),text(input.q(),"q",200),choice(input.use_yn()==null?"Y":input.use_yn(),"use_yn",Set.of("Y","N")),today());
        var columns=new ArrayList<ExportColumn<Map<String,Object>>>();
        String[][] labels={{"dev_nm","이름"},{"mng_no","관리번호"},{"affiliation_name","소속"},{"emp_type_name","직원구분"},{"grade_name","등급"},{"main_job_name","주 직무"},{"status","상태"},{"current_project_name","현재 프로젝트"},{"current_assignment_count","현재 투입 건수"},{"current_month_mm","이번 달 MM"},{"latest_assignment_end_dt","철수 예정일"},{"dflt_untprc_amt","월 단가"}};
        for(var label:labels){String key=label[0];columns.add(new ExportColumn<>(key,label[1],false,Set.of("current_assignment_count","current_month_mm","dflt_untprc_amt").contains(key),row->row.get(key)));}
        byte[] bytes=excel.create("직원구분="+Objects.toString(q.empType(),"전체")+", 직무="+Objects.toString(q.job(),"전체")+", 상태="+Objects.toString(q.status(),"전체")+", 검색="+Objects.toString(q.q(),"")+", 사용="+q.use_yn(),user.name(),LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,mapper.exportRows(q));
        return new DeveloperFile("개발자풀_"+today().format(DateTimeFormatter.BASIC_ISO_DATE)+".xlsx",bytes);
    }
}
