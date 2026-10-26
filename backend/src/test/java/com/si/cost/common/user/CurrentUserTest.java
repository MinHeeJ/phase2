package com.si.cost.common.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserTest {
    @Test void fixedAdapterDoesNotConsultAccountOrRoleStorage() {
        var user = new CurrentUser();
        assertThat(user.userId()).isNull();
        assertThat(user.name()).isEqualTo("기본 사용자");
        assertThat(user.roleIds()).containsExactly(1L);
        assertThat(user.dataScope()).isEqualTo(DataScope.ALL);
    }
    @Test void permissionKeysRemainAllowedWithFutureColumnMapping() {
        var permissions = new PermissionService();
        for (String action : List.of("read", "create", "update", "delete", "upload", "download", "confirm")) {
            assertThat(permissions.can("UI-27." + action)).isTrue();
            assertThat(PermissionService.AUTH_COLUMNS).containsKey(action);
        }
    }
    @Test void sensitiveAnnotationDoesNotMaskCurrentResponses() throws Exception {
        assertThat(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(new Amount(200000L))).get("amount").asLong())
                .isEqualTo(200000L);
    }
    record Amount(@Sensitive Long amount) { }
}
