package com.sicost.common.billing;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.common.billing.BillingSupportModels.*;

@Mapper
public interface BillingRevenueQuery {
    List<Revenue> monthlyRevenue(Period period);
    List<CashReceipt> cashReceipts(Period period);
    Supply confirmedSupply(ProjectMonth month);
    Balance balance(long billId);
}
