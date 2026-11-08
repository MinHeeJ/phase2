package com.sicost.common.security;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SensitiveAggregatePolicy {
    public enum Audience { SENSITIVE, SALES, PROJECT_MANAGER, NONE }
    public enum ReasonCode { PERMISSION_DENIED, SMALL_COHORT, DIFFERENCING_RISK }
    public record Amounts(BigDecimal revenue,BigDecimal contractAmount,BigDecimal cost,BigDecimal profit,
                          BigDecimal profitRate,BigDecimal costRate,BigDecimal average) {}
    public record Result(BigDecimal revenue,BigDecimal contractAmount,BigDecimal cost,BigDecimal profit,
                         BigDecimal profitRate,BigDecimal costRate,BigDecimal average,ReasonCode reasonCode) {}
    public record Cohort(Set<Long> developerIds,List<Set<Long>> otherPublishedCohorts,boolean safetyProven) {
        public Cohort { developerIds=Set.copyOf(developerIds);otherPublishedCohorts=otherPublishedCohorts.stream().map(Set::copyOf).toList(); }
    }
    private static final Set<String> FILTERS=Set.of("year","month","partId","projectId","contractType","status");
    public void requireAllowedFilters(Set<String> filters) {
        if(filters==null || !FILTERS.containsAll(filters)) throw AuthorizationService.forbidden();
    }
    public Result project(Audience audience,Cohort cohort,Amounts amounts) {
        ReasonCode reason=null;
        if(audience==Audience.NONE || audience==Audience.PROJECT_MANAGER) reason=ReasonCode.PERMISSION_DENIED;
        else if(audience!=Audience.SENSITIVE) {
            if(cohort.developerIds().size()<3) reason=ReasonCode.SMALL_COHORT;
            else if(!cohort.safetyProven() || cohort.otherPublishedCohorts().stream().anyMatch(other->
                !other.equals(cohort.developerIds()) && other.stream().anyMatch(cohort.developerIds()::contains))) reason=ReasonCode.DIFFERENCING_RISK;
        }
        return new Result(amounts.revenue(),amounts.contractAmount(),reason==null?amounts.cost():null,
            reason==null?amounts.profit():null,reason==null?amounts.profitRate():null,
            reason==null?amounts.costRate():null,reason==null?amounts.average():null,reason);
    }
}
