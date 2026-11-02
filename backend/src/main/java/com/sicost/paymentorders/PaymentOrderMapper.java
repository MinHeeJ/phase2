package com.sicost.paymentorders;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.paymentorders.PaymentOrderModels.*;

@Mapper
public interface PaymentOrderMapper {
    List<Order> list(String month);
    String monthStatus(String month);
    void lockMonth(String month);
    List<Long> lockPayments(String month);
    int save(Write write);
    int reschedule(Write write);
    void audit(Audit audit);
}
