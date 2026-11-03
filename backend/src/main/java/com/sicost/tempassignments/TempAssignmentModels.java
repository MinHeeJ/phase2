package com.sicost.tempassignments;

import java.math.BigDecimal;
import java.util.List;

public final class TempAssignmentModels {
    private TempAssignmentModels() {}
    public record Write(long need_id, long dev_id, String bgn_ym, String end_ym,
            BigDecimal mth_mm_qty, String tmpr_asgn_src_cd) {}
    public record Row(long id, long need_id, long dev_id, String bgn_ym, String end_ym,
            BigDecimal mth_mm_qty, String tmpr_asgn_src_cd, BigDecimal mtch_scr,
            String status, Long asgn_id) {}
    public record TempAssignment(long need_id, long dev_id, String bgn_ym, String end_ym,
            BigDecimal mth_mm_qty, String tmpr_asgn_src_cd, BigDecimal mtch_scr,
            long id, String status, Long asgn_id, List<String> warnings) {}
    public record TempAssignmentPage(List<TempAssignment> items, int page, int size, long total) {}
    public record Query(Long need_id, Long dev_id, Long oppt_id, String status, String from,
            String to, String order, int page, int size, long offset) {}
    public record Need(long need_id, long oppt_id, String use_yn) {}
    public record Insert(Write input, BigDecimal score, Long actor) {}
    public record Release(long id, Long actor) {}
    public record History(String id, String type, String before, String after, Long actor) {}
}
