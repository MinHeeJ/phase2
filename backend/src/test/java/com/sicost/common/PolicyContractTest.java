package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.PageQuery;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.security.DataScope;
import com.sicost.common.security.PermissionService;
import com.sicost.common.security.Sensitive;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PolicyContractTest {
    @Test
    void currentUserIsApprovedPolicyNotAQueriedRole() {
        var current = new CurrentUser();
        assertThat(current.get().userId()).isNull();
        assertThat(current.get().name()).isEqualTo("기본 사용자");
        assertThat(current.get().roleIds()).containsExactly(1L);
        assertThat(current.dataScope()).isEqualTo(DataScope.ALL);
        assertThat(DataScope.values()).containsExactly(DataScope.ALL, DataScope.PART, DataScope.PROJECT, DataScope.SELF);
    }

    @Test
    void permissionKeysAlwaysAllowAndPreserveFutureColumnMapping() {
        var permissions = new PermissionService();
        for (String action : List.of("read", "create", "update", "delete", "upload", "download", "confirm")) {
            assertThat(permissions.can("UI-27." + action)).isTrue();
            assertThat(PermissionService.AUTH_COLUMNS).containsKey(action);
        }
        assertThat(PermissionService.AUTH_COLUMNS.get("delete")).isEqualTo("del_auth_yn");
    }

    record SensitiveValue(@Sensitive String account) {}
    @Test
    void sensitiveAnnotationDoesNotMaskCurrentResponse() throws Exception {
        assertThat(new ObjectMapper().writeValueAsString(new SensitiveValue("visible"))).contains("visible");
    }

    @Test
    void paginationUsesOneBasedDefaultsAndAllowlistedOrder() {
        var query = PageQuery.of(null, null, null);
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(50);
        assertThat(query.offset()).isZero();
        assertThat(PageQuery.of(2, 20, "name,desc").orderBy(Map.of("name", "cd_nm"), "sort_seq ASC", "dtl_cd ASC"))
                .isEqualTo("cd_nm DESC, dtl_cd ASC");
    }

    @Test
    void invalidPageIsRejected() { assertThatThrownBy(() -> PageQuery.of(0, 50, null)).hasMessageContaining("페이지"); }
    @Test
    void invalidSizeIsRejected() { assertThatThrownBy(() -> PageQuery.of(1, 30, null)).hasMessageContaining("목록"); }
    @Test
    void arbitrarySqlSortIsRejected() {
        assertThatThrownBy(() -> PageQuery.of(1, 50, "name;DROP TABLE,desc").orderBy(Map.of("name", "cd_nm"), "sort_seq ASC", "dtl_cd ASC"))
                .hasMessageContaining("정렬");
    }
    @Test
    void timeDependentTestsUseFixedSeoulOctober() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        assertThat(YearMonth.now(clock)).isEqualTo(YearMonth.of(2026, 10));
    }
}
