package com.sicost.paymentorders;

import java.time.LocalDate;
import java.util.List;

public final class PaymentOrderModels {
    private PaymentOrderModels() {}
    public record Order(int pay_odr_no, LocalDate planned_date, boolean paid) {}
    public record Orders(List<Order> items, String month_status) {}
    public record Write(String month, Order order, Long actor) {}
    public record Audit(String key, String type, String before, String after, Long actor) {}
}
