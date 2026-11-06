package com.sicost.common.security;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.sicost.common.api.ApiException;

@Component
public class SensitiveAggregatePolicy {
    public static final String PRIVACY_REASON = "AGGREGATE_PRIVACY";
    private static final Set<String> FILTERS = Set.of("year","month","partId","projectId","contractType","status");
    public enum Audience { SENSITIVE, SALES, PM }
    public record Evidence(Set<Long> developerIds, boolean differencingSafetyProven) {
        public Evidence { developerIds = Set.copyOf(developerIds); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Metrics(BigDecimal revenue, BigDecimal contractAmount, BigDecimal cost, BigDecimal profit,
                          BigDecimal profitRate, BigDecimal costRate, BigDecimal average, String reasonCode) {}
    public void validateFilters(Set<String> filterNames) {
        if (filterNames == null || !FILTERS.containsAll(filterNames)) throw ApiException.forbidden();
    }
    public boolean disclose(Audience audience, Evidence evidence) {
        return audience == Audience.SENSITIVE || (audience == Audience.SALES && evidence != null &&
            evidence.developerIds().size() >= 3 && evidence.differencingSafetyProven());
    }
    /** Calculations precede projection. Missing proof is denial, never zero or a fabricated aggregate. */
    public Metrics project(Audience audience, Evidence evidence, Metrics calculated) {
        Objects.requireNonNull(calculated);
        if (disclose(audience,evidence)) return calculated;
        return new Metrics(calculated.revenue(),calculated.contractAmount(),null,null,null,null,null,PRIVACY_REASON);
    }
}
