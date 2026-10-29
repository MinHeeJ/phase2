package com.sicost.receipts;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.receipts.BillingReceiptModels.*;

@Mapper
public interface BillingReceiptMapper {
    BillState lockBill(long id);
    Header header(long id);
    Totals totals(long id);
    List<Header> amendments(long id);
    List<DetailLine> details(long id);
    List<Change> history(long id);
    List<Receipt> list(long id);
    Receipt row(ReceiptKey key);
    String monthStatus(String month);
    long insert(ReceiptWrite write);
    int update(ReceiptWrite write);
    int delete(ReceiptKey key);
    void audit(ReceiptAudit audit);
}
