package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.common.security.SensitiveAggregatePolicy.*;
import com.sicost.common.security.SensitiveAggregatePolicy;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class SensitiveAggregatePolicyTest {
    SensitiveAggregatePolicy policy=new SensitiveAggregatePolicy();
    Evidence evidence(boolean sensitive,boolean sales,boolean proven,Set<Long> members,List<Set<Long>> released) {
        return new Evidence(true,sensitive,sales,proven,members,released);
    }
    @Test void fewerThanThreeDistinctDevelopersSuppressesAllRelatedCostValues() {
        var result=policy.project(new Amounts(BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,null),
            evidence(false,true,true,Set.of(1L,2L),List.of()));
        assertThat(result.cost()).isNull();assertThat(result.profit()).isNull();assertThat(result.rate()).isNull();
        assertThat(result.costRate()).isNull();assertThat(result.average()).isNull();
        assertThat(result.reasonCode()).isEqualTo(Reason.INSUFFICIENT_MEMBERS);
        assertThat(result.revenue()).isEqualTo(BigDecimal.TEN);assertThat(result.contractAmount()).isEqualTo(BigDecimal.TEN);
    }
    @Test void fiveDevelopersStillSuppressesWhenDifferenceDisclosesOne() {
        var result=policy.evaluate(evidence(false,true,true,Set.of(1L,2L,3L,4L,5L),List.of(Set.of(1L,2L,3L,4L))));
        assertThat(result.visible()).isFalse();assertThat(result.reasonCode()).isEqualTo(Reason.DIFFERENCE_DISCLOSURE);
    }
    @Test void combinationsOfCohortsCannotDiscloseSmallIntersections() {
        var result=policy.evaluate(evidence(false,true,true,Set.of(1L,2L,3L,4L,5L,6L),List.of(Set.of(1L,2L,3L,7L,8L,9L),Set.of(1L,4L,5L,7L,10L,11L))));
        assertThat(result.visible()).isFalse();
    }
    @Test void threeMembersAndProvenSafetyAllowsSalesAggregate() {
        assertThat(policy.evaluate(evidence(false,true,true,Set.of(1L,2L,3L),List.of())).visible()).isTrue();
    }
    @Test void unprovenSafetyIsClosed() {
        assertThat(policy.evaluate(evidence(false,true,false,Set.of(1L,2L,3L),List.of())).reasonCode()).isEqualTo(Reason.UNPROVEN_SAFETY);
    }
    @Test void projectManagerNeverReceivesCostAggregateEvenWithLargeCohort() {
        assertThat(policy.evaluate(evidence(false,false,true,Set.of(1L,2L,3L,4L,5L),List.of())).reasonCode()).isEqualTo(Reason.SCOPE_RESTRICTED);
    }
    @Test void sensitiveGrantIsStillBoundToRowScope() {
        assertThat(policy.evaluate(new Evidence(false,true,true,true,Set.of(1L),List.of())).visible()).isFalse();
        assertThat(policy.evaluate(evidence(true,false,false,Set.of(1L),List.of())).visible()).isTrue();
    }
}
