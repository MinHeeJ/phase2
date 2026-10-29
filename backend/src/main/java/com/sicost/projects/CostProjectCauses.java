package com.sicost.projects;

import com.sicost.common.validation.CostValues;
import java.math.*;
import java.util.*;
import static com.sicost.projects.CostProjectModels.*;

public final class CostProjectCauses {
    private CostProjectCauses() {}
    public record Basis(Source source, BigDecimal planned, BigDecimal actual, long cost,
            long costRateExcess, long freeCost) {}
    public static Causes calculate(Basis b) {
        BigDecimal excess = b.actual().subtract(b.planned()).max(BigDecimal.ZERO);
        long over = b.actual().signum() == 0 ? 0 : CostValues.signedWon(
            BigDecimal.valueOf(b.cost()).multiply(excess).divide(b.actual(), 12, RoundingMode.HALF_UP));
        var p = b.source();
        var items = new ArrayList<>(List.of(
            new Cause("OVER_MM",over,"max(실제 MM−계획 MM,0) × MM당 원가",p.owner_name(),"투입 계획 확인","UI-11",p.prj_id()),
            new Cause("COST_RATE",b.costRateExcess(),"인력별 max(배분 원가−개인 매출×원가 기준율,0) 합계",p.owner_name(),"단가 재협상 검토","UI-32",p.prj_id()),
            new Cause("FREE_SUPPORT",b.freeCost(),"무상지원 배분 원가 (총원가에 이미 포함)",p.owner_name(),"무상지원 범위 확인","UI-04",p.prj_id())));
        items.sort(Comparator.comparingLong(Cause::amount).reversed());
        String primary = items.get(0).amount() > 0 ? items.get(0).cause_code() : null;
        Long progress = "TK".equals(p.cntrct_ty_cd()) && p.trgt_cst_amt() != null && p.progress_rt() != null
            ? CostValues.signedWon(BigDecimal.valueOf(b.cost()).subtract(
                BigDecimal.valueOf(p.trgt_cst_amt()).multiply(p.progress_rt()).movePointLeft(2))) : null;
        Long residual = progress == null ? null : Math.subtractExact(progress,
            Math.addExact(over,Math.addExact(b.costRateExcess(),b.freeCost())));
        return new Causes(List.copyOf(items),progress,residual,primary);
    }
}
