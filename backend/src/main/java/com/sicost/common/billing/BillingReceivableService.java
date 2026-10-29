package com.sicost.common.billing;

import com.sicost.common.api.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.common.billing.BillingSupportModels.*;

@Service
@Transactional(readOnly = true)
public class BillingReceivableService {
    private final BillingRevenueQuery query;
    private final Clock clock;

    public BillingReceivableService(BillingRevenueQuery query, Clock clock) {
        this.query = query;
        this.clock = clock;
    }

    public Balance balance(long billId) {
        if (billId < 1) throw BillingRules.invalid("id");
        var balance = query.balance(billId);
        if (balance == null) throw ApiException.notFound("청구가 없습니다.");
        return balance;
    }

    public void requireReceipt(Balance locked, long proposedAmount, long replacedAmount) {
        if (!"CONFIRM".equals(locked.bill_stts_cd()) || !"N".equals(locked.amnd_yn())) {
            throw BillingRules.stale();
        }
        if (proposedAmount < 1 || replacedAmount < 0 || replacedAmount > locked.received_amt()) {
            throw BillingRules.invalid("rcpt_amt");
        }
        long other = BillingRules.subtract(locked.received_amt(), replacedAmount, "rcpt_amt");
        long remaining = BillingRules.subtract(locked.adjustedTotal(), other, "rcpt_amt");
        if (proposedAmount > remaining) {
            throw ApiException.conflict("OVER_RECEIPT", "입금액이 미수 잔액을 초과합니다.", "rcpt_amt");
        }
    }

    public void requireAdjustment(Balance locked, long adjustmentTotal) {
        if (!"CONFIRM".equals(locked.bill_stts_cd()) || !"N".equals(locked.amnd_yn())) {
            throw BillingRules.stale();
        }
        if (adjustmentTotal >= 0) throw BillingRules.invalid("sply_amt");
        long adjusted = BillingRules.add(locked.adjustedTotal(), adjustmentTotal, "sply_amt");
        if (adjusted < locked.received_amt()) {
            throw ApiException.conflict("REFUND_REQUIRED", "입금액보다 작아지는 수정 청구는 환불 처리가 필요합니다.", "sply_amt");
        }
    }

    public Integer reminderStage(LocalDate due, long balance) {
        if (due == null || balance <= 0) return null;
        long days = ChronoUnit.DAYS.between(due, com.sicost.common.validation.ProjectValues.today(clock));
        return days >= 60 ? 3 : days >= 30 ? 2 : days >= 7 ? 1 : 0;
    }

    public LocalDate nextNotificationDate(LocalDate due, long balance) {
        Integer stage = reminderStage(due, balance);
        if (stage == null || stage == 3) return null;
        return due.plusDays(stage == 0 ? 7 : stage == 1 ? 30 : 60);
    }
}
