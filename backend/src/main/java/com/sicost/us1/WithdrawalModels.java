package com.sicost.us1;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.*;
import java.util.List;

public final class WithdrawalModels {
    private WithdrawalModels() {}
    public record Query(int year, String period, String ext, String job, String q,
            boolean dispatchWithin60, int page, int size, String sort) {}
    public record Source(long asgn_id, String asgn_no, long dev_id, String dev_nm,
            String mng_no, long prj_id, String prj_nm, String job_cd, String job_nm,
            LocalDate bgn_dt, LocalDate end_dt, LocalDate plan_end_dt,
            String ext_psblty_cd, String ext_psblty_nm, String asgn_stts_cd,
            LocalDateTime updated_at, int extend_count, boolean early_end,
            String replacement_name, String cntrct_ty_cd, String prj_stts_cd,
            String use_yn, LocalDate dispatch_first_dt, Long next_asgn_id,
            String next_prj_nm, LocalDate next_bgn_dt) {}
    public record Row(@JsonUnwrapped Source source, long remaining_days,
            LocalDate dispatch_expiry_dt, String dispatch_display, String dispatch_tone,
            String next_display, boolean chance_locked, String chance_lock_reason,
            List<LocalDate> dispatch_alert_dates) {}
    public record Summary(long soonPeople, long noNextPeople) {}
    public record Page(List<Row> rows, long total, int page, int size, Summary summary) {}
    public record Snapshot(LocalDate today, List<Row> rows, Summary summary) {}
    public record ExportFile(String name, byte[] bytes) {}
}
