package com.sicost.common;

import com.sicost.common.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProductionSecurityGuardTest {
    @ParameterizedTest @ValueSource(strings={"prod","production"})
    void productionBypassPreventsContextStartup(String profile) {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",Map.of("DEV_AUTH_BYPASS","true")));
            context.register(ProductionSecurityGuard.class);
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }
    @Test void productionWithNoMailOrNewEnvironmentSettingStartsNormally() {
        var environment=new MockEnvironment();environment.setActiveProfiles("production");
        new ProductionSecurityGuard(environment).afterPropertiesSet();
    }
    @Test void mixedTestProfileCannotHideProduction() {
        var environment=new MockEnvironment().withProperty("DEV_AUTH_BYPASS","true");environment.setActiveProfiles("production","test");
        assertThatThrownBy(()->new ProductionSecurityGuard(environment).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
    }
    @Test void explicitlyNonproductionConfigurationDoesNotActivateProductionGate() {
        var environment=new MockEnvironment().withProperty("DEV_AUTH_BYPASS","true");environment.setActiveProfiles("test");
        new ProductionSecurityGuard(environment).afterPropertiesSet();
    }
    @Test void falseBypassDoesNotBlockProduction() {
        var environment=new MockEnvironment().withProperty("DEV_AUTH_BYPASS","false");environment.setActiveProfiles("prod");
        new ProductionSecurityGuard(environment).afterPropertiesSet();
    }
    @Test void releaseRequiresEveryAcceptanceAndOperationalReceipt() {
        var gate=new SecurityDeploymentGate();
        Map<String,SecurityDeploymentGate.Receipt> acceptance=new HashMap<>();
        Map<SecurityDeploymentGate.Check,SecurityDeploymentGate.Receipt> deployment=new EnumMap<>(SecurityDeploymentGate.Check.class);
        assertThat(gate.incomplete(acceptance,deployment)).contains("AC-1601","AC-1620","TWO_NODE_SESSION_REVOCATION","DATABASE_BACKUP_RESTORE");
        assertThatThrownBy(()->gate.requireReady(acceptance,deployment)).isInstanceOf(IllegalStateException.class);
        for(int id=1601;id<=1620;id++) acceptance.put("AC-"+id,new SecurityDeploymentGate.Receipt(true,"runner receipt"));
        for(var check:SecurityDeploymentGate.Check.values()) deployment.put(check,new SecurityDeploymentGate.Receipt(true,"runner receipt"));
        gate.requireReady(acceptance,deployment);
        acceptance.put("AC-1618",new SecurityDeploymentGate.Receipt(true,""));
        assertThat(gate.incomplete(acceptance,deployment)).containsExactly("AC-1618");
    }
}
