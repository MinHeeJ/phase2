package com.sicost.developers;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class DeveloperModels {
    private DeveloperModels() {}
    public record DeveloperSave(String mng_no,String dev_nm,String emp_se_cd,String main_job_cd,
        Long vndr_ptnr_id,String pstn_cd,String duty_se_cd,String grd_cd,List<String> sub_job,List<String> skill,
        Long bsn_part_id,LocalDate hire_dt,String tel_no,String email_addr,String rmrk_desc,Long dflt_untprc_amt) {}
    public record DeveloperListItem(long dev_id,String mng_no,String dev_nm,String affiliation_name,
        String emp_se_cd,String emp_type_name,String grd_cd,String grade_name,String main_job_cd,String main_job_name,
        String status,String current_project_name,long current_assignment_count,BigDecimal current_month_mm,
        boolean over_one_mm,LocalDate latest_assignment_end_dt,Long dflt_untprc_amt,String use_yn,LocalDate rsgn_dt) {}
    public record DeveloperRow(long dev_id,String mng_no,String dev_nm,String emp_se_cd,String main_job_cd,
        Long vndr_ptnr_id,String pstn_cd,String duty_se_cd,String grd_cd,Long bsn_part_id,LocalDate hire_dt,
        String tel_no,String email_addr,String rmrk_desc,Long dflt_untprc_amt,LocalDateTime updated_at) {
        public DeveloperSave input(List<String> jobs,List<String> skills) {
            return new DeveloperSave(mng_no,dev_nm,emp_se_cd,main_job_cd,vndr_ptnr_id,pstn_cd,duty_se_cd,grd_cd,jobs,skills,bsn_part_id,hire_dt,tel_no,email_addr,rmrk_desc,dflt_untprc_amt);
        }
    }
    public record DeveloperDetail(@JsonUnwrapped DeveloperListItem item,Long vndr_ptnr_id,String pstn_cd,
        String duty_se_cd,List<String> sub_job,List<String> skill,Long bsn_part_id,LocalDate hire_dt,
        String tel_no,String email_addr,String rmrk_desc,@Sensitive LatestSalary latest_salary,LocalDateTime updated_at,
        Map<String,String> code_names,String sales_part_name) {}
    public record LatestSalary(String month,@Sensitive long slry_amt) {}
    public record DeveloperSaveResult(DeveloperDetail developer,boolean changed) {}
    public record DeveloperRead(long id,LocalDate today,String month,String withdrawalMonth) {}
    public record DeveloperCode(String group,String code) {}
    public record DeveloperCodeName(String group,String code,String name) {}
    public record DeveloperChild(long id,String code) {}
    public record DeveloperWrite(long id,String no,DeveloperSave input,Set<String> changed,Long actor) {}
    public record DeveloperHistory(String id,String type,String column,String before,String after,Long actor) {}
}
