package com.sicost.common;

import com.sicost.common.security.CurrentUser;
import com.sicost.common.security.DashboardActions;
import com.sicost.common.security.PermissionService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardPermissionContractTest {
    @ParameterizedTest
    @CsvSource({"getDashboard,UI-16.read","exportDashboard,UI-16.download"})
    void approvedOperationUsesOnlyItsPermissionPlaceholder(String operationId,String key) {
        assertThat(DashboardActions.key(operationId)).isEqualTo(key);
        assertThat(PermissionService.AUTH_COLUMNS).containsKey(key.substring(key.indexOf('.')+1));
        var permissions=spy(new PermissionService());
        assertThat(DashboardActions.can(permissions,operationId)).isTrue();
        verify(permissions).can(key);
        verifyNoMoreInteractions(permissions);
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings={"runClosingCheck","confirmClosing","createBill","approveQuote","sendNotification","createTemporaryAssignment","getDashboardSection"})
    void noWriteOrSectionOperationGetsDashboardPermission(String operationId) {
        var permissions=spy(new PermissionService());
        assertThatIllegalArgumentException().isThrownBy(()->DashboardActions.can(permissions,operationId));
        verifyNoInteractions(permissions);
    }

    @Test void existingContractHasNoGlobalAuthenticationRequirement() throws Exception {
        Map<?,?> contract;
        try (var input=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract=new Yaml().load(input);
        }
        assertThat((java.util.List<?>)contract.get("security")).isEmpty();
    }

    @Test void anonymousActorRemainsTheExistingNullUser() {
        assertThat(new CurrentUser().userId()).isNull();
        assertThat(new CurrentUser().name()).isEqualTo("기본 사용자");
    }
}
