package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.security.*;
import com.sicost.common.security.SecurityModels.*;
import com.sicost.common.security.SensitiveAggregatePolicy.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SensitiveAggregatePolicyTest {
    final SensitiveAggregatePolicy policy=new SensitiveAggregatePolicy();
    final Amounts amounts=new Amounts(new BigDecimal("1000"),new BigDecimal("1200"),new BigDecimal("600"),new BigDecimal("400"),new BigDecimal("40"),new BigDecimal("60"),new BigDecimal("200"));
    Cohort cohort(Set<Long> ids) { return new Cohort(ids,List.of(),true); }
    void hidden(Result result,ReasonCode reason) { assertThat(result.cost()).isNull();assertThat(result.profit()).isNull();assertThat(result.profitRate()).isNull();assertThat(result.costRate()).isNull();assertThat(result.average()).isNull();assertThat(result.reasonCode()).isEqualTo(reason);assertThat(result.revenue()).isEqualByComparingTo("1000");assertThat(result.contractAmount()).isEqualByComparingTo("1200"); }
    @Test void twoDevelopersHideEveryCostDerivedMetricButNotAllowedRevenue() { hidden(policy.project(Audience.SALES,cohort(Set.of(1L,2L)),amounts),ReasonCode.SMALL_COHORT); }
    @Test void safeThreePersonAggregatePreservesExistingArithmeticExactly() { var result=policy.project(Audience.SALES,cohort(Set.of(1L,2L,3L)),amounts);assertThat(result.cost()).isEqualTo(amounts.cost());assertThat(result.profitRate()).isEqualTo(amounts.profitRate());assertThat(result.reasonCode()).isNull(); }
    @Test void fivePersonAggregateWithOnePersonDifferenceIsNotSafe() { hidden(policy.project(Audience.SALES,new Cohort(Set.of(1L,2L,3L,4L,5L),List.of(Set.of(1L,2L,3L,4L)),true),amounts),ReasonCode.DIFFERENCING_RISK); }
    @Test void unprovenFilterCombinationIsSuppressedEvenWithFivePeople() { hidden(policy.project(Audience.SALES,new Cohort(Set.of(1L,2L,3L,4L,5L),List.of(),false),amounts),ReasonCode.DIFFERENCING_RISK); }
    @Test void projectManagerNeverReceivesProfitOrPersonalCost() { hidden(policy.project(Audience.PROJECT_MANAGER,cohort(Set.of(1L,2L,3L)),amounts),ReasonCode.PERMISSION_DENIED); }
    @Test void sensitiveAuthorizedCalculationIsNotChangedByProjection() { assertThat(policy.project(Audience.SENSITIVE,cohort(Set.of(1L)),amounts).cost()).isEqualTo(amounts.cost()); }
    @ParameterizedTest @ValueSource(strings={"developerName","developerId","job","grade","employeeType","vendor","includeCost","excludeCost","assignmentPeriod"})
    void unsafeFilterNeverReachesAggregateQuery(String filter) { AuthorizationTest.status(()->policy.requireAllowedFilters(Set.of("month",filter)),403); }
    @Test void allowedCoarseFiltersStillRequireCohortCheck() { policy.requireAllowedFilters(Set.of("month","partId","projectId","contractType","status"));hidden(policy.project(Audience.SALES,cohort(Set.of(1L,2L)),amounts),ReasonCode.SMALL_COHORT); }
    record Developer(String name,@Sensitive Long salary,String phone,String email,List<Payment> payments) {}
    record Payment(@Sensitive Long cost,String month) {}
    @Test void publicProjectionRemovesSensitiveNestedValuesAndContactsWithoutChangingSource() {
        var grant=new Permission(5L,"일반 사용자(PM)",DataScope.PROJECT,false,false,false,"UI-02","YNNNNNN",true,false);
        var scope=new ScopePredicate(11L,null,new Assignments(List.of(),List.of(10L)),List.of(grant));
        var source=new Developer("합성 개발자",123456L,"비공개 연락값","비공개 연락값",List.of(new Payment(98765L,"2026-10")));
        var output=new SensitiveProjection(new ObjectMapper()).project(source,scope,new Resource(null,null,10L,null));
        assertThat(output.has("salary")).isFalse();assertThat(output.has("phone")).isFalse();assertThat(output.has("email")).isFalse();assertThat(output.at("/payments/0").has("cost")).isFalse();assertThat(source.salary()).isEqualTo(123456L);
    }
    @Test void partStandardChangeNeedsItsIndependentFlag() {
        var authorization=mock(AuthorizationService.class);var row=new Resource(null,1L,null,null);
        var grant=new Permission(4L,"영업 파트장",DataScope.PART,false,false,false,"UI-15","YYYNNNN",false,false);
        var scope=new ScopePredicate(11L,null,new Assignments(List.of(1L),List.of()),List.of(grant));
        when(authorization.requireRow("UI-15.update",row)).thenReturn(scope);
        doCallRealMethod().when(authorization).requirePartStandard(row);
        AuthorizationTest.status(()->authorization.requirePartStandard(row),403);
    }
}
