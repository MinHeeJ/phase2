package com.sicost.common;

import com.sicost.common.security.ClosingActions;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.security.PermissionService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class ClosingActionsTest {
    @Test void approvedPublicOperationsHaveFuturePermissionHooks() throws Exception {
        Map<?, ?> contract;
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract = new org.yaml.snakeyaml.Yaml().load(input);
        }
        Map<?, ?> paths = (Map<?, ?>) contract.get("paths");
        var permissions = new PermissionService();
        int operations = 0;
        for (var entry : paths.entrySet()) {
            if (!entry.getKey().toString().startsWith("/api/closing")) continue;
            for (Object value : ((Map<?, ?>) entry.getValue()).values()) {
                if (!(value instanceof Map<?, ?> operation) || !operation.containsKey("operationId")) continue;
                String id = operation.get("operationId").toString();
                assertThat((java.util.List<?>) operation.get("security")).isEmpty();
                assertThat(permissions.can(ClosingActions.key(id))).isTrue();
                operations++;
            }
        }
        assertThat(operations).isEqualTo(9);
        assertThat(ClosingActions.key("confirmClosing")).isEqualTo("UI-14.confirm");
        assertThat(ClosingActions.key("reopenClosing")).isEqualTo("UI-14.reopen");
        assertThat(ClosingActions.key("runClosingCheck")).isEqualTo("UI-14.update");
        assertThat(ClosingActions.key("getClosing")).isEqualTo("UI-14.read");
        assertThat(ClosingActions.DOWNLOAD).isEqualTo("UI-14.download");
    }

    @Test void anonymousActorRemainsNullableNotSeedUserOne() {
        assertThat(new CurrentUser().userId()).isNull();
    }

    @Test void unknownOperationCannotSilentlyAcquireAPermissionKey() {
        assertThatThrownBy(() -> ClosingActions.key("getMonthClose")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClosingActions.key(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
