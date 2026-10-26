package com.sicost.partchanges;

import java.time.LocalDateTime;

public final class PartChangeModels {
    private PartChangeModels() {}

    public record PartChangeRequest(Long bsn_part_id, String aply_bgn_ym, String rsn_desc) {}
    public record PartChange(Long bsn_part_id, String aply_bgn_ym, String rsn_desc,
                             Long chg_user_id, LocalDateTime chg_at, String partName) {}
    public record PartChangeProject(long prj_id, String cntrct_ty_cd, String use_yn, Long bsn_part_id) {}
    public record PartChangeWrite(long id, PartChangeRequest input, Long actor) {}
    public record PartChangeAudit(String id, String before, String after, String reason, Long actor) {}
}
