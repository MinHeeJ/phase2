package com.sicost.receipts;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.receipts.BillingReceiptModels.*;

@Mapper
public interface BillingReceiptMapper {
    BillState bill(long id);
    BillState lockBill(long id);
    ReceiptRow receipt(ReceiptKey key);
    List<ReceiptRow> receipts(long id);
    Long insert(ReceiptWrite write);
    int update(ReceiptWrite write);
    int delete(ReceiptKey key);
    long amendmentTotal(long id);
    long receivedExcluding(ReceiptKey key);
    String monthStatus(String month);
    String billing(long id);
    List<String> details(long id);
    List<String> amendments(long id);
    List<String> history(long id);
    List<String> warnings(long id);
    void audit(ReceiptAudit audit);
}
