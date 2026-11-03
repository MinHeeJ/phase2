package com.sicost.opportunitiesops;

import com.sicost.opportunities.OpportunityModels.CostSource;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.sicost.opportunitiesops.OpportunityLifecycleModels.*;

@Service
public class OpportunityQuoteVerifier {
    private final OpportunityLifecycleMapper mapper;
    public OpportunityQuoteVerifier(OpportunityLifecycleMapper mapper){this.mapper=mapper;}
    public record Snapshot(long revenue,long cost,BigDecimal margin,boolean known,String fingerprint,Set<Long> sourceIds,boolean zeroSources) {}
    public Snapshot calculate(Row row,List<Need> needs,java.time.LocalDate today) {
        var sources=mapper.costSources(today);var sourceIds=new TreeSet<Long>();
        BigDecimal revenue=BigDecimal.ZERO,cost=BigDecimal.ZERO;boolean known=!needs.isEmpty(),zeroSources=true;
        var signatures=new ArrayList<String>();
        for(var n:needs){
            BigDecimal mm=BigDecimal.valueOf(n.psn_cnt()).multiply(n.mth_mm_qty()).multiply(BigDecimal.valueOf(n.mth_cnt()));
            if(n.expc_untprc_amt()==null||n.expc_untprc_amt()<1)known=false;
            revenue=revenue.add(mm.multiply(BigDecimal.valueOf(n.expc_untprc_amt()==null?0:n.expc_untprc_amt())));
            var population=sources.stream().filter(s->Objects.equals(s.job(),n.job_cd())&&Objects.equals(s.grade(),n.grd_cd())).toList();
            if(population.size()<2){
                population=n.grd_cd()==null?Collections.emptyList():sources.stream().filter(s->Objects.equals(s.grade(),n.grd_cd())).toList();
                if(population.isEmpty())population=sources.stream().filter(s->Objects.equals(s.job(),n.job_cd())).toList();
            }
            BigDecimal average=BigDecimal.ZERO;
            if(population.isEmpty())known=false;
            else {
                average=population.stream().map(s->s.labor().add(s.expense())).reduce(BigDecimal.ZERO,BigDecimal::add)
                    .divide(BigDecimal.valueOf(population.size()),0,RoundingMode.DOWN);
                population.forEach(s->sourceIds.add(s.id()));
                if(population.stream().anyMatch(s->s.labor().signum()!=0||s.expense().signum()!=0))zeroSources=false;
            }
            population.stream().map(s->new CostSource(s.developer(),s.job(),s.grade(),s.id(),s.month(),s.labor().add(s.expense())))
                .sorted(Comparator.comparingLong(CostSource::dev_id)).forEach(s->signatures.add(s.toString()));
            cost=cost.add(mm.multiply(average));
        }
        long r=revenue.setScale(0,RoundingMode.DOWN).longValueExact(),c=cost.setScale(0,RoundingMode.DOWN).longValueExact();
        BigDecimal margin=r==0?null:BigDecimal.valueOf(r-c).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(r),2,RoundingMode.HALF_UP);
        return new Snapshot(r,c,margin,known,String.join(";",signatures),Set.copyOf(sourceIds),zeroSources);
    }
    public boolean zeroProof(Snapshot s,String evidence) {
        if(!s.known()||!s.zeroSources()||s.cost()!=0||s.sourceIds().isEmpty()||evidence==null||!evidence.matches("cst\\.mth_cst:[0-9]+(?:,[0-9]+)*"))return false;
        try {
            Set<Long> ids=new TreeSet<>();for(String id:evidence.substring("cst.mth_cst:".length()).split(","))ids.add(Long.parseLong(id));
            return ids.equals(s.sourceIds());
        }catch(NumberFormatException e){return false;}
    }
    public boolean current(Row row,Snapshot s) {
        return Objects.equals(row.revenue(),s.revenue())&&Objects.equals(row.cost(),s.cost())&&
            (row.margin()==null?s.margin()==null:s.margin()!=null&&row.margin().compareTo(s.margin())==0);
    }
    public String costStatus(Row row,Snapshot s) {
        if(!s.known())return "UNKNOWN";
        if(s.cost()>0)return "VERIFIED";
        return "APPROVED".equals(row.approval())&&zeroProof(s,row.evidence())&&Objects.equals(row.validation(),s.fingerprint())?"PROVEN_ZERO":"UNKNOWN";
    }
}
