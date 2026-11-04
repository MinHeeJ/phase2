package com.sicost.common;

import com.sicost.common.ai.AiSourceReads;
import com.sicost.common.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiSourceReadsTest {
    PlatformTransactionManager transactions;
    AiSourceReads reads;
    @BeforeEach void setup() {
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        reads = new AiSourceReads(transactions);
    }
    @Test void realZeroIsSuccessfulButAbsentSourceIsUnavailable() {
        assertThat(reads.required(() -> 0L).status()).isEqualTo("OK");
        assertThat(reads.required(() -> 0L).value()).isZero();
        assertThat(reads.required(() -> null).status()).isEqualTo("DATA_UNAVAILABLE");
        assertThat(reads.required(() -> null).value()).isNull();
    }
    @Test void requiredDependencyOutageIs503WithoutSqlLeakOrFabricatedZero() {
        assertThatThrownBy(() -> reads.required(() -> { throw new DataAccessResourceFailureException("private source SQL"); }))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(503); assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE");
                assertThat(e.getMessage()).doesNotContain("private source SQL");
            });
        verify(transactions).rollback(any());
    }
    @Test void optionalOutageIsPartialAndSeparateSuccessRemainsUsable() {
        var failed = reads.optional(() -> { throw new DataAccessResourceFailureException("private source SQL"); });
        var success = reads.optional(() -> 7L);
        assertThat(failed.status()).isEqualTo("PARTIAL"); assertThat(failed.value()).isNull();
        assertThat(failed.errorCode()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(success.status()).isEqualTo("OK"); assertThat(success.value()).isEqualTo(7L);
        verify(transactions).rollback(any()); verify(transactions).commit(any());
    }
    @Test void forbiddenOrInvalidInputsMustNotBecomeMissingOrPartialData() {
        assertThatThrownBy(() -> reads.optional(() -> { throw new ApiException(403,"FORBIDDEN","접근 거절",null); }))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
    }
    @Test void everySourceReadHasIndependentReadOnlyRepeatableReadTransaction() {
        reads.required(() -> 1L);
        verify(transactions).getTransaction(argThat(definition -> definition.isReadOnly()
            && definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ
            && definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }
}
