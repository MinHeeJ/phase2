package com.sicost.phases;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class ProjectPhaseModels {
    private ProjectPhaseModels() {}
    public record PhaseProject(String use_yn, String prj_stts_cd) {}
    public record PhaseKey(long id, long phsId, LocalDate today) {}
    public record Phase(long phs_id, long prj_id, int phs_seq, String phs_nm, BigDecimal wght_rt,
        LocalDate plan_bgn_dt, LocalDate plan_end_dt, LocalDate cmpl_dt, String status) {}
    public record PeriodInput(LocalDate plan_bgn_dt, LocalDate plan_end_dt) {}
    public record PhaseWrite(PhaseKey key, PeriodInput period, Long actor) {}
    public record PhaseAudit(String id, String column, String before, String after, Long actor) {}
}
