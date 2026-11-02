package com.sicost.paymentorders;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.paymentorders.PaymentOrderModels.*;

@Service
@Transactional(readOnly=true)
public class PaymentOrderService {
    private final PaymentOrderMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    public PaymentOrderService(PaymentOrderMapper mapper, CurrentUser user, PermissionService permissions) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;
    }
    public Orders list(String month) {
        PaymentOrderInput.month(month);permissions.can("UI-20.read");
        return new Orders(mapper.list(month),Objects.equals(mapper.monthStatus(month),"CLOSED")?"CLOSED":"OPEN");
    }
    @Transactional
    public Orders save(String month, List<Order> items) {
        PaymentOrderInput.month(month);permissions.can("UI-20.update");
        mapper.lockMonth(month);
        if("CLOSED".equals(mapper.monthStatus(month)))
            throw ApiException.conflict("CLOSED_MONTH","마감된 월의 지급 차수는 변경할 수 없습니다.","month");
        mapper.lockPayments(month);
        var before=mapper.list(month);
        for(var order:items) {
            var old=before.stream().filter(r->r.pay_odr_no()==order.pay_odr_no()).findFirst().orElse(null);
            if(old!=null && old.paid() && !Objects.equals(old.planned_date(),order.planned_date()))
                throw ApiException.conflict("ALREADY_PAID","지급 완료 건이 있는 차수는 변경할 수 없습니다.","planned_date");
        }
        for(var order:items) {
            var old=before.stream().filter(r->r.pay_odr_no()==order.pay_odr_no()).findFirst().orElse(null);
            if(old!=null && Objects.equals(old.planned_date(),order.planned_date())) continue;
            var write=new Write(month,order,user.userId());
            mapper.save(write);mapper.reschedule(write);
            mapper.audit(new Audit(month+":"+order.pay_odr_no(),old==null?"INSERT":"UPDATE",
                old==null?null:old.planned_date().toString(),order.planned_date().toString(),user.userId()));
        }
        return list(month);
    }
}
