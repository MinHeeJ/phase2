package com.sicost.developersops;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class DeveloperLifecycleModels {
    private DeveloperLifecycleModels() {}
    public record DeveloperOption(long id, String mngNo, String name, String mainJob, String empType) {}
    public record DeveloperState(long dev_id, String dev_nm, String use_yn) {}
    public record DeveloperDate(long id, LocalDate today) {}
    public record DeveloperStateWrite(long id, String use, Long actor) {}
    public record DeveloperAudit(long id, String type, String before, String after, Long actor) {}
    public record DeveloperBlock(long id, String name, long current_assignment_count, String code, String reason) {}
    public record DeveloperDisableResult(List<Long> disabled_ids, List<DeveloperBlock> blocked, int disabled_count, int blocked_count) {}
    public record DeveloperAssignment(long asgn_id, long prj_id, String prj_nm, String job_cd, String job_name,
        LocalDate bgn_dt, LocalDate end_dt, BigDecimal asgn_rt, String asgn_stts_cd) {}
    public record DeveloperMonthlyCost(String cst_ym, String emp_se_cd, @Sensitive long slry_amt,
        @Sensitive long alwnc_amt, @Sensitive long sins_cmpny_amt, @Sensitive long rtrpay_acml_amt,
        @Sensitive long indv_lbr_cst_amt, @Sensitive long real_pay_amt) {}
    public record DeveloperSalary(String month, @Sensitive long slry_amt) {}
    public record DeveloperChange(LocalDateTime changed_at, String table_name, String record_id,
        String change_type, String item, String before, String after, String actor) {}
    public record DeveloperReadQuery(long id, String filter, String from, String to, String order, int size, long offset) {}
    public record DeveloperOptionQuery(String q, LocalDate today) {}
    public record DeveloperExportQuery(String empType, String job, String status, String q, String use_yn, LocalDate today) {}
    public record DeveloperFile(String filename, byte[] bytes) {}
}
