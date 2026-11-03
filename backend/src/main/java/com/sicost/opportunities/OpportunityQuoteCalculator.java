package com.sicost.opportunities;

import java.math.*;
import java.time.YearMonth;
import java.util.*;
import static com.sicost.opportunities.OpportunityModels.*;

public final class OpportunityQuoteCalculator {
    private OpportunityQuoteCalculator() {}
    public static Calculation calculate(Save input,List<CostSource> sources,Map<String,Long> defaults,BigDecimal targetMargin) {
        BigDecimal revenue=BigDecimal.ZERO,cost=BigDecimal.ZERO;boolean unknown=false;
        List<NeedWrite> needs=new ArrayList<>();Set<String> warnings=new LinkedHashSet<>();List<String> signatures=new ArrayList<>();
        for(var n:input.needs()) {
            Long price=n.expc_untprc_amt()==null?defaults.get(n.job_cd()):n.expc_untprc_amt();
            if(price==null||price<=0)throw OpportunityInput.error("needs["+needs.size()+"].expc_untprc_amt");
            var same=sources.stream().filter(s->s.job_cd().equals(n.job_cd())&&Objects.equals(s.grd_cd(),n.grd_cd())).toList();
            List<CostSource> selected=same;
            if(same.size()<2) {
                selected=n.grd_cd()==null?Collections.emptyList():sources.stream().filter(s->Objects.equals(s.grd_cd(),n.grd_cd())).toList();
                warnings.add("원가 표본 부족: 등급 전체 평균 적용");
                if(selected.isEmpty()) { selected=sources.stream().filter(s->s.job_cd().equals(n.job_cd())).toList(); warnings.add("등급 원가 없음: 직무 전체 평균 적용"); }
            }
            boolean missing=selected.isEmpty();unknown|=missing;
            long unit=missing?0:selected.stream().map(CostSource::amount).reduce(BigDecimal.ZERO,BigDecimal::add)
                .divide(BigDecimal.valueOf(selected.size()),0,RoundingMode.DOWN).longValueExact();
            if(missing)warnings.add("원가 미확인: 별도 승인 필요");
            selected.stream().sorted(Comparator.comparingLong(CostSource::dev_id)).forEach(s->signatures.add(s.toString()));
            var normalized=new NeedInput(n.need_id(),n.job_cd(),n.grd_cd(),n.psn_cnt(),n.bgn_ym(),n.mth_cnt(),n.mth_mm_qty(),price,n.rmrk_desc());
            BigDecimal quantity=BigDecimal.valueOf(n.psn_cnt()).multiply(n.mth_mm_qty()).multiply(BigDecimal.valueOf(n.mth_cnt()));
            revenue=revenue.add(quantity.multiply(BigDecimal.valueOf(price)));cost=cost.add(quantity.multiply(BigDecimal.valueOf(unit)));
            BigDecimal denominator=BigDecimal.ONE.subtract(targetMargin.movePointLeft(2));
            if(denominator.signum()<=0)throw OpportunityInput.error("QUOTE_TARGET_MARGIN");
            long target=BigDecimal.valueOf(unit).divide(denominator.multiply(BigDecimal.valueOf(10000)),0,RoundingMode.CEILING).multiply(BigDecimal.valueOf(10000)).longValueExact();
            needs.add(new NeedWrite(0,n.need_id(),normalized,unit,target,needs.size()));
            if(outside(input.bgn_ym(),input.mth_cnt(),n.bgn_ym(),n.mth_cnt()))warnings.add("필요 인력 기간이 기회 기간 밖입니다. 실제 필요 기간으로 예측합니다.");
        }
        long r=revenue.setScale(0,RoundingMode.DOWN).longValueExact(),c=cost.setScale(0,RoundingMode.DOWN).longValueExact();
        return new Calculation(List.copyOf(needs),r,c,rate(BigDecimal.valueOf(r).subtract(BigDecimal.valueOf(c)),r),unknown,List.copyOf(warnings),String.join(";",signatures));
    }
    public static boolean outside(String start,int count,String needStart,int needCount) {
        var s=YearMonth.parse(start);var n=YearMonth.parse(needStart);
        return n.isBefore(s)||n.plusMonths(needCount-1L).isAfter(s.plusMonths(count-1L));
    }
    public static BigDecimal rate(BigDecimal numerator,long denominator) {
        return denominator==0?null:numerator.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP);
    }
    public static boolean sameNeeds(List<NeedRow> old,List<NeedWrite> next) {
        if(old.size()!=next.size())return false;
        Map<Long,NeedRow> rows=new HashMap<>();old.forEach(r->rows.put(r.need_id(),r));
        return next.stream().allMatch(n->{var previous=rows.get(n.needId());return previous!=null&&normalized(previous.input()).equals(normalized(n.input()));});
    }
    private static NeedInput normalized(NeedInput n) { return new NeedInput(n.need_id(),n.job_cd(),n.grd_cd(),n.psn_cnt(),n.bgn_ym(),n.mth_cnt(),n.mth_mm_qty().setScale(2),n.expc_untprc_amt(),n.rmrk_desc()); }
    public static boolean retains(Row row,List<NeedRow> old,Calculation next,BigDecimal tolerance) {
        return "APPROVED".equals(row.approval_state())&&Objects.equals(row.expc_sls_amt(),next.revenue())&&Objects.equals(row.expc_cst_amt(),next.cost())
            &&sameNeeds(old,next.needs())&&row.aprv_mrgn_rt()!=null&&next.margin()!=null&&row.aprv_mrgn_rt().subtract(next.margin()).abs().compareTo(tolerance)<=0;
    }
}
