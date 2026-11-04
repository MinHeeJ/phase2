package com.sicost.common.ai;

import com.sicost.common.api.ApiException;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AiSourceReads {
    public record Result<T>(String status, T value, String errorCode) {
        @Override public String toString() { return "SourceResult[status=" + status + ",value=redacted]"; }
    }
    private final TransactionTemplate transaction;
    public AiSourceReads(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public <T> Result<T> required(Supplier<T> read) {
        try { return execute(read); }
        catch (DataAccessException | TransactionException ex) {
            throw new ApiException(503, "SERVICE_UNAVAILABLE", "필수 원천 서비스를 조회할 수 없습니다.", null);
        }
    }
    public <T> Result<T> optional(Supplier<T> read) {
        try { return execute(read); }
        catch (DataAccessException | TransactionException ex) {
            return new Result<>("PARTIAL", null, "SERVICE_UNAVAILABLE");
        }
    }
    private <T> Result<T> execute(Supplier<T> read) {
        // Catch outside execute: a failed PostgreSQL transaction has already rolled back before another area is read.
        T value = transaction.execute(status -> read.get());
        return new Result<>(value == null ? "DATA_UNAVAILABLE" : "OK", value, null);
    }
}
