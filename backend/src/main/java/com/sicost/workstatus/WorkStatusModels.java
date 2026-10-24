package com.sicost.workstatus;

import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class WorkStatusModels {
    private WorkStatusModels() {}
    public record WorkStatusInput(String work_stts_cd, LocalDate bgn_dt, LocalDate end_dt,
                                  String paid_yn, String rmrk_desc) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkStatusRow(long work_stts_hist_id, long dev_id, String work_stts_cd,
                                LocalDate bgn_dt, LocalDate end_dt, String paid_yn, String rmrk_desc) {}
    public record WorkStatusKey(long devId, long histId) {}
    public record WorkStatusQuery(long devId, String status, int size, long offset, String order) {}
    public record WorkStatusWrite(long devId, long histId, WorkStatusInput input, Long actor) {}
    public record WorkStatusAudit(long histId, String type, String before, String after, Long actor) {}
    public record WorkStatusResignation(long devId, LocalDate date, Long actor) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkStatusRemoval(long work_stts_hist_id, boolean deleted, LocalDate rsgn_dt) {}
}
