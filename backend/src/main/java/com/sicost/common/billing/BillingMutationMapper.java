package com.sicost.common.billing;

import org.apache.ibatis.annotations.Mapper;
import static com.sicost.common.billing.BillingSupportModels.*;

@Mapper
public interface BillingMutationMapper {
    State state(long billId);
    State lockBill(long billId);
    void numberLock(String prefix);
    long maximumNumber(String prefix);
    void audit(Audit audit);
}
