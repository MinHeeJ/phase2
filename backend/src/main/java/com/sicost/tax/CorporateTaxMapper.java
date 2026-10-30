package com.sicost.tax;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.tax.CorporateTaxModels.*;

@Mapper
public interface CorporateTaxMapper {
    void lockYear(String year);
    Annual plan(String year);
    List<Snapshot> snapshots(String year);
    List<History> history(String year);
    void savePlan(PlanWrite write);
    void appendHistory(PlanWrite write);
    List<Payment> payments(String year);
    List<Payment> lockPayments(String year);
    String monthStatus(String month);
    long savePayment(PaymentWrite write);
    void auditPayment(PaymentAudit audit);
}
