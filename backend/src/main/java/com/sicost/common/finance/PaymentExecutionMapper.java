package com.sicost.common.finance;

import java.time.*;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentExecutionMapper {
    record Payment(long pay_id, String pay_ym, LocalDate planned_date, long planned_amount,
            String status, String source, String category, Long source_id, Long parent_id,
            LocalDateTime updated_at, String recipient, String bank, String account, String depositor) {}
    record Completion(long paymentId, LocalDate date, long amount, boolean paid, Long actor) {}
    Payment lock(long id);
    boolean activeChild(long id);
    long executed(long id);
    String monthStatus(String month);
    void insertExecution(Completion write);
    int updatePayment(Completion write);
    void synchronizeTax(Completion write);
    void audit(Completion write);
    Long taxPaymentId(long taxPayId);
}
