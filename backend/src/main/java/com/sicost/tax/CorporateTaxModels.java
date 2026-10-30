package com.sicost.tax;

import java.time.*;
import java.util.List;

public final class CorporateTaxModels {
    private CorporateTaxModels() {}
    public record Annual(Long annual_amount, String reason) {}
    public record Snapshot(String month, boolean closed, Long amount) {}
    public record Month(String month, boolean closed, Long amount, String warning) {}
    public record History(Long before_amount, long after_amount, String reason, LocalDateTime changed_at) {}
    public record Plan(int year, Long annual_amount, String reason, List<Month> months,
            List<History> history, boolean editable) {}
    public record PlanInput(long annual_amount, String reason) {}
    public record PlanWrite(String year, PlanInput input, Long before, Long actor) {}
    public record Payment(Long tax_pay_id, String tax_pay_se_cd, LocalDate planned_date,
            Long planned_amount, LocalDate actual_date, Long actual_amount) {}
    public record PaymentInput(String tax_pay_se_cd, LocalDate planned_date, long planned_amount,
            LocalDate actual_date, Long actual_amount, LocalDateTime updated_at) {}
    public record PaymentWrite(String year, PaymentInput input, Long actor) {}
    public record Suggestion(String tax_pay_se_cd, LocalDate suggested_date, Long suggested_amount) {}
    public record Payments(int year, List<Payment> payments, List<Suggestion> suggestions, List<String> locked_types) {}
    public record PaymentAudit(long id, String before, String after, Long actor) {}
}
