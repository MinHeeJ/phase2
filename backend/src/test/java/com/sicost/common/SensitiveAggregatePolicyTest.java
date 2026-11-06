package com.sicost.common;

import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SensitiveAggregatePolicyTest {
    final SensitiveAggregatePolicy policy=new SensitiveAggregatePolicy();
    final SensitiveAggregatePolicy.Metrics source=new SensitiveAggregatePolicy.Metrics(
        new BigDecimal("100"),new BigDecimal("200"),new BigDecimal("40"),new BigDecimal("60"),
        new BigDecimal("60"),new BigDecimal("40"),new BigDecimal("20"),null);
    Set<Long> people(int count) { Set<Long> ids=new HashSet<>(); for(long id=1;id<=count;id++) ids.add(id); return ids; }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void smallSalesGroupsWithholdEveryDependentMetric(int count) {
        var result=policy.project(SensitiveAggregatePolicy.Audience.SALES,new SensitiveAggregatePolicy.Evidence(people(count),true),source);
        assertThat(result.cost()).isNull();assertThat(result.profit()).isNull();assertThat(result.profitRate()).isNull();
        assertThat(result.costRate()).isNull();assertThat(result.average()).isNull();
        assertThat(result.reasonCode()).isEqualTo("AGGREGATE_PRIVACY");
        assertThat(result.revenue()).isEqualTo(source.revenue());assertThat(result.contractAmount()).isEqualTo(source.contractAmount());
    }
    @Test void fivePeopleDoNotProveDifferencingSafety() {
        assertThat(policy.project(SensitiveAggregatePolicy.Audience.SALES,new SensitiveAggregatePolicy.Evidence(people(5),false),source).cost()).isNull();
    }
    @Test void threeDistinctPeopleAndPositiveProofAllowOwnPartAggregate() {
        assertThat(policy.project(SensitiveAggregatePolicy.Audience.SALES,new SensitiveAggregatePolicy.Evidence(people(3),true),source)).isSameAs(source);
    }
    @Test void missingProofIsClosed() { assertThat(policy.project(SensitiveAggregatePolicy.Audience.SALES,null,source).cost()).isNull(); }
    @Test void pmNeverReceivesCostEvenWithSafeGroup() {
        assertThat(policy.project(SensitiveAggregatePolicy.Audience.PM,new SensitiveAggregatePolicy.Evidence(people(5),true),source).cost()).isNull();
    }
    @Test void sensitiveReaderPreservesCalculatedValues() { assertThat(policy.project(SensitiveAggregatePolicy.Audience.SENSITIVE,null,source)).isSameAs(source); }
    @ParameterizedTest @ValueSource(strings={"developerId","developerName","job","grade","employeeType","vendor","assignmentPeriod","includeCost","excludeCost"})
    void forbiddenFilterCannotCreateDifferencingChannel(String key) {
        assertThatThrownBy(()->policy.validateFilters(Set.of("month",key))).isInstanceOf(com.sicost.common.api.ApiException.class)
            .extracting(e->((com.sicost.common.api.ApiException)e).status()).isEqualTo(403);
    }
    @Test void approvedFiltersAreAccepted() { policy.validateFilters(Set.of("year","month","partId","projectId","contractType","status")); }
}
