package com.sicost.receipts;

import com.sicost.common.api.ApiException;
import com.sicost.common.validation.BillingRules;
import com.sicost.common.validation.BillingValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.receipts.BillingReceiptModels.*;

@Service("billingReceiptBalanceService")
@Transactional(readOnly = true)
public class BillingReceivableService {
    private final BillingReceiptMapper mapper;
    public BillingReceivableService(BillingReceiptMapper mapper) { this.mapper = mapper; }

    public Balance balance(long billId) {
        BillingReceiptInput.id(billId, "id");
        var header = mapper.header(billId);
        if (header == null) throw ApiException.notFound("청구가 없습니다.");
        var totals = mapper.totals(billId);
        long adjusted = BillingValues.money(totals.adjusted_total_amt(), Long.MIN_VALUE, "adjusted_total_amt");
        long received = BillingValues.money(totals.received_amt(), 0, "received_amt");
        return new Balance(adjusted, received, BillingRules.receivable(
            BillingRules.Status.valueOf(header.bill_stts_cd()), adjusted, received));
    }
}
