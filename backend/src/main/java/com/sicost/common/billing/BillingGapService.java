package com.sicost.common.billing;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.common.billing.BillingSupportModels.*;

@Service
@Transactional(readOnly = true)
public class BillingGapService {
    private final BillingRevenueQuery query;

    public BillingGapService(BillingRevenueQuery query) { this.query = query; }

    public Gap compare(ProjectMonth month, long basisSupply, boolean attendanceMissing, boolean freeOnly) {
        var confirmed = query.confirmedSupply(month);
        long difference = BillingRules.subtract(basisSupply, confirmed.confirmed_supply_amt(), "difference_amt");
        BigDecimal tolerance = BigDecimal.valueOf(basisSupply).abs()
            .multiply(new BigDecimal("0.005")).max(new BigDecimal("10000"));
        String judgment;
        if (attendanceMissing) judgment = "근태 미입력";
        else if (freeOnly && basisSupply == 0 && confirmed.confirmed_supply_amt() == 0) judgment = "맞음(무상)";
        else if (confirmed.confirmed_count() == 0 && basisSupply > 0) judgment = "청구 없음";
        else if (BigDecimal.valueOf(difference).abs().compareTo(tolerance) <= 0) judgment = "맞음";
        else judgment = difference > 0 ? "적게 청구" : "많이 청구";
        return new Gap(basisSupply, confirmed.confirmed_supply_amt(), difference,
            tolerance, judgment, attendanceMissing);
    }
}
