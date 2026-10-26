package com.sicost.projects;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sicost.common.validation.ProjectPhases;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class ProjectModels {
    private ProjectModels() {}
    public record ProjectSave(String prj_nm, Long cust_ptnr_id, String cntrct_ty_cd,
            String prj_stts_cd, BigDecimal win_prblt_rt, LocalDate cntrct_dt,
            LocalDate cntrct_bgn_dt, LocalDate cntrct_end_dt, LocalDate prfm_bgn_dt,
            Long bsn_part_id, String stlm_mthd_cd, Long pm_dev_id, String free_sprt_yn,
            Long quot_amt, Long init_cntrct_amt, BigDecimal trgt_mrgn_rt, Long trgt_cst_amt,
            String indty_cd, String duty_fld_nm, List<String> prj_skl, Long prev_prj_id,
            String rmrk_desc) {
        public ProjectSave financial(Long part, BigDecimal margin, Long cost) {
            return new ProjectSave(prj_nm, cust_ptnr_id, cntrct_ty_cd, prj_stts_cd,
                win_prblt_rt, cntrct_dt, cntrct_bgn_dt, cntrct_end_dt, prfm_bgn_dt,
                part, stlm_mthd_cd, pm_dev_id, free_sprt_yn, quot_amt, init_cntrct_amt,
                margin, cost, indty_cd, duty_fld_nm, prj_skl, prev_prj_id, rmrk_desc);
        }
        public Map<String,Object> columns() {
            Map<String,Object> v = new LinkedHashMap<>();
            v.put("prj_nm",prj_nm); v.put("cust_ptnr_id",cust_ptnr_id);
            v.put("cntrct_ty_cd",cntrct_ty_cd); v.put("prj_stts_cd",prj_stts_cd);
            v.put("win_prblt_rt",win_prblt_rt); v.put("cntrct_dt",cntrct_dt);
            v.put("cntrct_bgn_dt",cntrct_bgn_dt); v.put("cntrct_end_dt",cntrct_end_dt);
            v.put("prfm_bgn_dt",prfm_bgn_dt); v.put("bsn_part_id",bsn_part_id);
            v.put("stlm_mthd_cd",stlm_mthd_cd); v.put("pm_dev_id",pm_dev_id);
            v.put("free_sprt_yn",free_sprt_yn); v.put("quot_amt",quot_amt);
            v.put("init_cntrct_amt",init_cntrct_amt); v.put("trgt_mrgn_rt",trgt_mrgn_rt);
            v.put("trgt_cst_amt",trgt_cst_amt); v.put("indty_cd",indty_cd);
            v.put("duty_fld_nm",duty_fld_nm); v.put("prev_prj_id",prev_prj_id);
            v.put("rmrk_desc",rmrk_desc); return v;
        }
    }
    public record ProjectListItem(long prj_id, String prj_no, String prj_nm,
            String custName, String cntrct_ty_cd, LocalDate cntrct_bgn_dt,
            LocalDate cur_end_dt, Long cur_cntrct_amt, long assignmentCount,
            Long bsn_part_id, String partName, String prj_stts_cd) {}
    public record ProjectPage(List<ProjectListItem> items, long total, int page,
            int size, long unassignedCount) {}
    public record ProjectRow(long prj_id, String prj_no, String prj_nm, Long cust_ptnr_id,
            String cntrct_ty_cd, String prj_stts_cd, BigDecimal win_prblt_rt,
            LocalDate cntrct_dt, LocalDate cntrct_bgn_dt, LocalDate cntrct_end_dt,
            LocalDate prfm_bgn_dt, Long bsn_part_id, String stlm_mthd_cd, Long pm_dev_id,
            String free_sprt_yn, Long quot_amt, Long init_cntrct_amt,
            BigDecimal trgt_mrgn_rt, Long trgt_cst_amt, String indty_cd,
            String duty_fld_nm, Long prev_prj_id, String rmrk_desc, Long cur_cntrct_amt,
            LocalDate cur_end_dt, BigDecimal cntrct_mm_qty, String use_yn,
            String custName, String partName, String pmName, long assignmentCount) {
        public ProjectSave input(List<String> skills) {
            return new ProjectSave(prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd,
                win_prblt_rt,cntrct_dt,cntrct_bgn_dt,cntrct_end_dt,prfm_bgn_dt,
                bsn_part_id,stlm_mthd_cd,pm_dev_id,free_sprt_yn,quot_amt,init_cntrct_amt,
                trgt_mrgn_rt,trgt_cst_amt,indty_cd,duty_fld_nm,skills,prev_prj_id,rmrk_desc);
        }
    }
    public record ProjectDetail(@JsonUnwrapped ProjectRow row, List<String> prj_skl) {}
    public record ProjectRead(long id, LocalDate today) {}
    public record ProjectCode(String group, String code) {}
    public record ProjectChild(long id, String code) {}
    public record ProjectWrite(String no, ProjectSave input, Long actor) {}
    public record ProjectUpdate(long id, Map<String,Object> changes, Long actor) {}
    public record ProjectPart(long id, String month, Long part, Long actor) {}
    public record ProjectPhase(long id, ProjectPhases.Plan plan, Long actor) {}
    public record ProjectHistory(String id, String type, String column, String before,
            String after, Long actor) {}
}
