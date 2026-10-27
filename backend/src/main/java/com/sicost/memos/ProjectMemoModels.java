package com.sicost.memos;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class ProjectMemoModels {
    private ProjectMemoModels() {}
    public record MemoInput(JsonNode actl_prgs_rt, String cmpl_work_desc, String plan_work_desc, String rqst_desc) {}
    public record ProjectState(long prj_id, String cntrct_ty_cd, String use_yn, String prj_stts_cd) {}
    public record MemoRow(long memo_id, long prj_id, LocalDate wrt_dt, BigDecimal actl_prgs_rt,
        String cmpl_work_desc, String plan_work_desc, String rqst_desc) {}
    public record WeeklyMemo(long memo_id, long prj_id, LocalDate wrt_dt, Integer actl_prgs_rt,
        String cmpl_work_desc, String plan_work_desc, String rqst_desc) {}
    public record MemoKey(long id, long memoId) {}
    public record MemoDate(long id, LocalDate today) {}
    public record MemoWrite(long id, MemoRow row, Long actor) {}
}
