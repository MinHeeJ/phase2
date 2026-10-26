package com.sicost.billstages;

import java.time.LocalDate;
import java.util.List;

public final class ProjectBillStageModels {
    private ProjectBillStageModels() {}

    public record ProjectState(long prj_id, String cntrct_ty_cd, String use_yn, Long cur_cntrct_amt) {}
    public record StageInput(Long bill_stg_id, String bill_stg_nm, long sply_amt, LocalDate bill_plan_dt) {}
    public record Stage(long bill_stg_id, String bill_stg_nm, long sply_amt, LocalDate bill_plan_dt,
                        int stg_odr_no, long vat_amt, boolean billed) {}
    public record Stages(List<Stage> items, Long cur_cntrct_amt, long totalSupplyAmount) {}
    public record StageWrite(long projectId, StageInput input, int ordinal, Long actor) {}
    public record StagePosition(long projectId, long stageId, int ordinal) {}
    public record StageKey(long projectId, long stageId) {}
    public record StageHistory(String projectId, String before, String after, Long actor) {}
}
