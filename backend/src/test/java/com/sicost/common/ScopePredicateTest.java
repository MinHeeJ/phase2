package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ScopePredicateTest {
    final ScopePredicate.Target partA=new ScopePredicate.Target(10L,100L,null,null);
    final ScopePredicate.Target projectB=new ScopePredicate.Target(20L,200L,null,null);
    ScopePredicate scope(List<ScopePredicate.Grant> grants) { return new ScopePredicate(1L,9L,grants); }
    ScopePredicate.Grant grant(long id,DataScope scope,Set<Long> parts,Set<Long> projects,boolean sensitive) {
        return new ScopePredicate.Grant(id,scope,parts,projects,sensitive,false);
    }
    @Test void roleActionCannotBorrowAnUnqualifiedRoleScope() {
        var access=scope(List.of(grant(4L,DataScope.PART,Set.of(10L),Set.of(),false)));
        assertThat(access.allows(partA)).isTrue();assertThat(access.allows(projectB)).isFalse();
        assertThatThrownBy(()->access.require(projectB)).isInstanceOf(com.sicost.common.api.ApiException.class)
            .extracting(e->((com.sicost.common.api.ApiException)e).status()).isEqualTo(404);
    }
    @Test void sensitiveRoleOutsideTargetCannotUnmaskOtherRoleRow() {
        var access=scope(List.of(grant(4L,DataScope.PART,Set.of(10L),Set.of(),false),grant(6L,DataScope.PROJECT,Set.of(),Set.of(200L),true)));
        assertThat(access.allows(partA)).isTrue();assertThat(access.sensitive(partA)).isFalse();assertThat(access.sensitive(projectB)).isTrue();
    }
    @Test void missingRelationshipsNeverBecomeAll() {
        for(var kind:List.of(DataScope.PART,DataScope.PROJECT)) assertThat(scope(List.of(grant(4L,kind,Set.of(),Set.of(),false))).allows(partA)).isFalse();
        assertThat(scope(List.of()).allows(partA)).isFalse();
    }
    @Test void selfMatchesOnlyRealActorOrLinkedDeveloper() {
        var access=scope(List.of(grant(5L,DataScope.SELF,Set.of(),Set.of(),false)));
        assertThat(access.allows(new ScopePredicate.Target(null,null,1L,null))).isTrue();
        assertThat(access.allows(new ScopePredicate.Target(null,null,null,9L))).isTrue();
        assertThat(access.allows(new ScopePredicate.Target(null,null,null,8L))).isFalse();
        assertThat(access.allows(new ScopePredicate.Target(null,null,null,null))).isFalse();
    }
    @Test void bulkPreflightRejectsMixedScopeBeforeCallerMutation() {
        var access=scope(List.of(grant(4L,DataScope.PART,Set.of(10L),Set.of(),false)));
        List<ScopePredicate.Target> changed=new ArrayList<>();
        assertThatThrownBy(()->{access.requireAll(List.of(partA,projectB));changed.add(partA);}).isInstanceOf(com.sicost.common.api.ApiException.class);
        assertThat(changed).isEmpty();
    }
    record PrivateRow(String name,@Sensitive Long salary,@Sensitive String account) {}
    record Nested(List<PrivateRow> rows) {}
    @Test void nestedSensitiveProjectionContainsNoRecoverableOriginals() {
        var projection=new SensitiveProjectionService(new ObjectMapper());
        var row=new Nested(List.of(new PrivateRow("합성 인물",123456L,"SYNTHETIC_ACCOUNT")));
        var noSensitive=scope(List.of(grant(4L,DataScope.PART,Set.of(10L),Set.of(),false)));
        var result=projection.project(row,noSensitive,partA);
        assertThat(result.path("rows").get(0).has("salary")).isFalse();assertThat(result.toString()).doesNotContain("123456","SYNTHETIC_ACCOUNT");
        var yesSensitive=scope(List.of(grant(1L,DataScope.ALL,Set.of(),Set.of(),true)));
        assertThat(projection.project(row,yesSensitive,partA).path("rows").get(0).path("salary").longValue()).isEqualTo(123456L);
    }
}
