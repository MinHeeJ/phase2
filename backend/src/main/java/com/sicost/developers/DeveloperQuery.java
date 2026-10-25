package com.sicost.developers;

import com.sicost.common.api.*;
import java.time.*;
import java.util.Set;

public record DeveloperQuery(int page,int size,long offset,String order,String empType,String job,String status,
    String q,String useYn,LocalDate today,String month,String withdrawalMonth) {
    public record Filters(String empType,String job,String status,String q,String useYn) {}
    public static DeveloperQuery of(DeveloperPaging paging,Filters filters,LocalDate today) {
        String emp=blank(filters.empType()),job=blank(filters.job()),status=blank(filters.status()),q=blank(filters.q());
        String use=filters.useYn()==null?"Y":filters.useYn();
        if(emp!=null && !Set.of("FT","CT","FL","OS").contains(emp))throw ApiException.badRequest("직원구분을 확인하세요.","empType");
        if(status!=null && !Set.of("퇴사","휴직","철수예정","투입중","대기").contains(status))throw ApiException.badRequest("상태를 확인하세요.","status");
        if(!Set.of("Y","N").contains(use))throw ApiException.badRequest("사용 여부를 확인하세요.","use_yn");
        if(job!=null && !job.matches("[A-Z0-9_]{1,30}"))throw ApiException.badRequest("직무를 확인하세요.","job");
        String order="dev_nm ASC, dev_id ASC";
        if(paging.sort()!=null && !paging.sort().isBlank()) {
            String[] bits=paging.sort().split(",",-1);
            Set<String> fields=Set.of("dev_nm","mng_no","affiliation_name","emp_se_cd","emp_type_name","grd_cd","grade_name","main_job_cd","main_job_name","status","current_project_name","current_month_mm","latest_assignment_end_dt","dflt_untprc_amt");
            if(bits.length!=2 || !fields.contains(bits[0]) || !Set.of("asc","desc").contains(bits[1]))throw ApiException.badRequest("정렬을 확인하세요.","sort");
            order=bits[0]+" "+bits[1]+" NULLS LAST, dev_id ASC";
        }
        return new DeveloperQuery(paging.page(),paging.size(),paging.toPageRequest().offset(),order,emp,job,status,q,use,today,YearMonth.from(today).toString(),YearMonth.from(today).plusMonths(2).toString());
    }
    private static String blank(String s){return s==null||s.isBlank()?null:s.trim();}
}
