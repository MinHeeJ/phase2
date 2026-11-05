package com.sicost.common.security;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SensitiveAggregatePolicy {
    public enum Reason { INSUFFICIENT_MEMBERS, DIFFERENCE_DISCLOSURE, SCOPE_RESTRICTED, UNPROVEN_SAFETY }
    public record Evidence(boolean inScope, boolean sensitiveView, boolean salesPartAggregate,
                           boolean safetyProven, Set<Long> developerIds, List<Set<Long>> publishedCohorts) {
        public Evidence {
            developerIds=Set.copyOf(developerIds);
            publishedCohorts=publishedCohorts.stream().map(Set::copyOf).toList();
        }
    }
    public record Decision(boolean visible, Reason reasonCode) {}
    public record Amounts(BigDecimal revenue, BigDecimal contractAmount, BigDecimal cost, BigDecimal profit,
                          BigDecimal rate, BigDecimal costRate, BigDecimal average, Reason reasonCode) {}
    public Decision evaluate(Evidence evidence) {
        if(evidence==null || !evidence.inScope()) return denied(Reason.SCOPE_RESTRICTED);
        if(evidence.sensitiveView()) return new Decision(true,null);
        if(!evidence.salesPartAggregate()) return denied(Reason.SCOPE_RESTRICTED);
        if(evidence.developerIds().size()<3) return denied(Reason.INSUFFICIENT_MEMBERS);
        if(!evidence.safetyProven()) return denied(Reason.UNPROVEN_SAFETY);
        // Any released cohort can be subtracted, intersected or combined with this result.
        // Partition by all released membership signatures, not just pairwise cohort sizes.
        var partitions=new java.util.HashMap<List<Boolean>,Set<Long>>();
        var all=new HashSet<>(evidence.developerIds());evidence.publishedCohorts().forEach(all::addAll);
        for(Long id:all) {
            var signature=new java.util.ArrayList<Boolean>();signature.add(evidence.developerIds().contains(id));
            evidence.publishedCohorts().forEach(c->signature.add(c.contains(id)));
            partitions.computeIfAbsent(List.copyOf(signature),k->new HashSet<>()).add(id);
        }
        if(partitions.values().stream().anyMatch(p->p.size()<3)) return denied(Reason.DIFFERENCE_DISCLOSURE);
        return new Decision(true,null);
    }
    public Amounts project(Amounts source,Evidence evidence) {
        var decision=evaluate(evidence);
        if(decision.visible()) return new Amounts(source.revenue(),source.contractAmount(),source.cost(),source.profit(),
            source.rate(),source.costRate(),source.average(),null);
        return new Amounts(source.revenue(),source.contractAmount(),null,null,null,null,null,decision.reasonCode());
    }
    private Decision denied(Reason reason) { return new Decision(false,reason); }
}
