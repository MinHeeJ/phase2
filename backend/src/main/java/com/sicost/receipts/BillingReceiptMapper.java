package com.sicost.receipts;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.receipts.BillingReceiptModels.*;

@Mapper
public interface BillingReceiptMapper {
    Root root(long id);
    Root lockRoot(long id);
    Long lockProject(long id);
    Map<String, Object> billing(long id);
    List<Map<String, Object>> details(long id);
    List<Map<String, Object>> amendments(long id);
    List<Map<String, Object>> history(long id);
    List<Receipt> receipts(long id);
    Receipt receipt(ReceiptKey key);
    String monthStatus(String month);
    Long insert(ReceiptWrite write);
    int update(ReceiptWrite write);
    int delete(ReceiptKey key);
    void audit(ReceiptAudit audit);
}
