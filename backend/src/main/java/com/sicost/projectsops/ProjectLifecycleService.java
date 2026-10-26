package com.sicost.projectsops;

import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.projectsops.ProjectLifecycleModels.*;

@Service
@Transactional(readOnly=true)
public class ProjectLifecycleService {
    private final ProjectLifecycleMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ExcelExportService excel;
    public ProjectLifecycleService(ProjectLifecycleMapper mapper,CurrentUser user,PermissionService permissions,Clock clock,ExcelExportService excel){
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;this.excel=excel;
    }
    private LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    private void action(String operation){permissions.can(ProjectActions.key(operation));}
    private ProjectState require(long id,boolean lock){
        if(id<1)throw ApiException.badRequest("프로젝트 번호를 확인하세요.","id");
        var row=lock?mapper.lockState(id):mapper.state(id);
        if(row==null)throw ApiException.notFound("프로젝트가 없습니다.");return row;
    }
    public List<ProjectOption> options(String q){action("listProjectOptions");return mapper.options(blank(q));}
    public List<ProjectAssignment> assignments(long id){action("listProjectAssignments");require(id,false);return mapper.assignments(id);}
    public List<ProjectHistoryEntry> history(long id){action("listProjectHistory");require(id,false);return mapper.history(Long.toString(id));}
    private Map<String,Object> detail(long id){
        var row=new LinkedHashMap<>(mapper.detail(new ProjectRead(id,today())));
        row.replaceAll((key,value)->value instanceof java.sql.Date d?d.toLocalDate():value instanceof java.sql.Timestamp t?t.toLocalDateTime():value);
        for(String key:List.of("cust_ptnr_id","win_prblt_rt","cntrct_dt","cntrct_bgn_dt","cntrct_end_dt","prfm_bgn_dt","bsn_part_id","stlm_mthd_cd","pm_dev_id","quot_amt","init_cntrct_amt","trgt_mrgn_rt","trgt_cst_amt","indty_cd","duty_fld_nm","prev_prj_id","rmrk_desc","cur_cntrct_amt","cur_end_dt","cntrct_mm_qty","custName","partName","pmName"))row.putIfAbsent(key,null);
        row.put("prj_skl",mapper.skills(id));return row;
    }
    @Transactional
    public Map<String,Object> disable(long id){
        action("updateProjectDisable");var row=require(id,true);
        if("Y".equals(row.useYn())){
            var usage=mapper.usage(id);if(usage.blocked())throw ApiException.conflict("PROJECT_IN_USE",usage.message(),null);
            mapper.changeState(new ProjectStateWrite(id,"N",user.userId()));
            mapper.audit(new ProjectAudit(Long.toString(id),"DISABLE","Y","N",user.userId()));
        }
        return detail(id);
    }
    @Transactional
    public Map<String,Object> enable(long id){
        action("updateProjectEnable");var row=require(id,true);
        if("N".equals(row.useYn())){
            mapper.changeState(new ProjectStateWrite(id,"Y",user.userId()));
            mapper.audit(new ProjectAudit(Long.toString(id),"UPDATE","N","Y",user.userId()));
        }
        return detail(id);
    }
    private static String blank(String value){return value==null||value.isBlank()?null:value.trim();}
    private static String choice(String value,String field,Set<String> choices){value=blank(value);if(value!=null&&!choices.contains(value))throw ApiException.badRequest("조회 조건을 확인하세요.",field);return value;}
    ProjectExportQuery exportQuery(Map<String,String> input){
        int year=today().getYear();
        if(input.containsKey("year"))try{year=Integer.parseInt(input.get("year"));}catch(NumberFormatException e){throw ApiException.badRequest("연도를 확인하세요.","year");}
        if(year<1||year>9999)throw ApiException.badRequest("연도를 확인하세요.","year");
        String type=choice(input.get("type"),"type",Set.of("TK","MO","DP","IN"));
        String status=choice(input.get("status"),"status",Set.of("PLAN","RUN","HOLD","END","LOST"));
        String part=blank(input.get("part"));Long partId=null;boolean unassigned="NONE".equals(part);
        if(part!=null&&!unassigned)try{if(!part.matches("[0-9]+")||(partId=Long.valueOf(part))<1)throw new NumberFormatException();}catch(NumberFormatException e){throw ApiException.badRequest("영업 파트를 확인하세요.","part");}
        var paging=new ProjectPaging(0,50,input.get("sort"));
        return new ProjectExportQuery(year,LocalDate.of(year,1,1),LocalDate.of(year,12,31),type,status,partId,unassigned,blank(input.get("q")),paging.order(),today());
    }
    public DownloadArtifact export(Map<String,String> input){
        action("getProjectExport");var query=exportQuery(input);
        var columns=new ArrayList<ExportColumn<Map<String,Object>>>();
        String[][] titles={{"prj_no","프로젝트ID"},{"prj_nm","프로젝트명"},{"custName","고객사"},{"cntrct_ty_cd","계약유형"},{"cntrct_bgn_dt","계약 시작일"},{"cur_end_dt","현재 종료일"},{"cur_cntrct_amt","계약금액"},{"assignmentCount","투입 인원"},{"partName","영업 파트"},{"prj_stts_cd","상태"}};
        for(var title:titles){String key=title[0];columns.add(new ExportColumn<>(key,title[1],false,Set.of("cur_cntrct_amt","assignmentCount").contains(key),r->r.get(key)));}
        String conditions="연도="+query.year()+", 계약유형="+Objects.toString(query.type(),"전체")+", 상태="+Objects.toString(query.status(),"종료·실주 제외")+", 영업 파트="+Objects.toString(input.get("part"),"전체")+", 검색="+Objects.toString(query.q(),"")+", 정렬="+new ProjectPaging(0,50,input.get("sort")).sort();
        byte[] bytes=excel.create(conditions,user.name(),LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,mapper.exportRows(query));
        return DownloadArtifact.workbook("프로젝트_"+query.year()+"_"+today().format(DateTimeFormatter.BASIC_ISO_DATE)+".xlsx",bytes);
    }
}
