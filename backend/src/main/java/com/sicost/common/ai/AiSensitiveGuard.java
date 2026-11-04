package com.sicost.common.ai;

import com.sicost.common.security.Sensitive;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AiSensitiveGuard {
    private final AiAccessGuard access;
    private final Environment environment;
    private final DataSource database;
    public AiSensitiveGuard(AiAccessGuard access, Environment environment, DataSource database) {
        this.access = access; this.environment = environment; this.database = database;
    }
    public void requireNonSensitive(boolean usesPersonalCost, boolean reverseDerivable) {
        access.requireDevelopment();
        if (usesPersonalCost || reverseDerivable) throw AiAccessGuard.forbidden();
    }
    public void requireIndependentVirtual(Set<Long> businessReferences) {
        access.requireDevelopment();
        if (businessReferences == null || !businessReferences.isEmpty()) throw AiAccessGuard.forbidden();
    }
    @Sensitive
    public void requireSyntheticDatabase() {
        access.requireDevelopment();
        if (!Arrays.asList(environment.getActiveProfiles()).contains("test") ||
                !environment.getProperty("sicost.ai.synthetic-test-enabled", Boolean.class, false)) {
            throw AiAccessGuard.forbidden();
        }
        try (var connection = database.getConnection()) {
            String catalog = connection.getCatalog();
            if (catalog == null || !catalog.matches("sicost_test_[0-9a-f]{32}")) throw AiAccessGuard.forbidden();
        } catch (SQLException ex) {
            throw new com.sicost.common.api.ApiException(503, "SERVICE_UNAVAILABLE", "시험 데이터 연결을 확인할 수 없습니다.", null);
        }
    }
}
