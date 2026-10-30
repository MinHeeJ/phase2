package com.sicost.payments;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.payments.FinancePaymentModels.*;

@Mapper
public interface FinancePaymentMapper {
    List<Payment> list(String month);
    Payment row(long id);
    Long root(long id);
    Long lock(long id);
    List<Payment> chain(long root);
    String monthStatus(String month);
    String nextOpen(String month);
    boolean activeCategory(String code);
    boolean activeBank(String code);
    boolean developer(long id);
    boolean partner(long id);
    List<PaymentChoice> categories();
    List<PaymentChoice> banks();
    List<String> missingSources(String month);
    long insert(PaymentWrite write);
    int update(PaymentWrite write);
    int state(PaymentState state);
    void execute(PaymentExecution execution);
    List<PaymentExecution> executions(long id);
    void reverse(PaymentExecution execution);
    void audit(PaymentAudit audit);
    void access(String description);
    List<Payment> candidates(String month);
    Long source(Payment payment);
    int generate(Payment payment);
    void taxActual(long root);
}
