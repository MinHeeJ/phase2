package com.sicost.issues;

import java.time.LocalDate;
import java.util.*;

public final class IssueModels {
    private IssueModels() {}
    public record IssueInput(String ttl_nm, String svrt_cd, String chrg_nm, LocalDate ddln_dt,
        String issue_stts_cd, String actn_desc) {
        public Map<String,Object> columns() {
            var values = new LinkedHashMap<String,Object>();
            values.put("ttl_nm",ttl_nm); values.put("svrt_cd",svrt_cd); values.put("chrg_nm",chrg_nm);
            values.put("ddln_dt",ddln_dt); values.put("issue_stts_cd",issue_stts_cd); values.put("actn_desc",actn_desc);
            return values;
        }
    }
    public record IssueCreate(long prj_id, IssueInput input) {}
    public record IssueRow(long issue_id, long prj_id, String ttl_nm, String svrt_cd, String chrg_nm,
        LocalDate ddln_dt, String issue_stts_cd, String actn_desc, LocalDate reg_dt, LocalDate end_dt) {
        public Map<String,Object> columns() {
            var values = new IssueInput(ttl_nm,svrt_cd,chrg_nm,ddln_dt,issue_stts_cd,actn_desc).columns();
            values.put("end_dt",end_dt); return values;
        }
    }
    public record IssueListRow(long issue_id, long prj_id, String ttl_nm, String svrt_cd, String chrg_nm,
        LocalDate ddln_dt, String issue_stts_cd, String actn_desc, LocalDate reg_dt, LocalDate end_dt, String prj_nm) {}
    public record IssuesData(List<IssueListRow> items, long total, int page, int size, long open_count) {}
    public record IssueProject(long prj_id, String use_yn, String prj_stts_cd) {}
    public record IssueQuery(String status, String severity, Long projectId, LocalDate today,
        int page, int size, long offset) {}
    public record IssueInsert(long projectId, IssueInput input, LocalDate today, LocalDate end, Long actor) {}
    public record IssueUpdate(long id, Map<String,Object> changes, Long actor) {}
    public record IssueHistory(String id, String column, String before, String after, Long actor) {}
}
