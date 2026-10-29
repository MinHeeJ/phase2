package com.sicost.receipts;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.receipts.BillingReceiptModels.*;

@Mapper
public interface BillingReceiptMapper {
    ReceiptBillingRow billing(long id);
    Long originalId(long id);
    List<ReceiptRow> list(long id);
    ReceiptRow row(ReceiptKey key);
    Long insert(ReceiptWrite write);
    int update(ReceiptWrite write);
    int delete(ReceiptKey key);
    List<ReceiptDetailLine> details(long id);
    List<ReceiptBillingRow> amendments(long id);
    List<ReceiptHistory> history(long id);
}
