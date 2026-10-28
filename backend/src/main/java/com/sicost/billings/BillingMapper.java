package com.sicost.billings;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.billings.BillingModels.*;

@Mapper
public interface BillingMapper {
    Project project(long id);
    Project lockProject(long id);
    Stage stage(StageKey key);
    List<SourceLine> sources(SourceKey key);
    String monthStatus(String month);
    BigDecimal vatRate(String month);
    void lockNumber(String prefix);
    int nextSerial(String prefix);
    int existingStage(StageKey key);
    int maxOrdinal(SourceKey key);
    StoredBill stored(long id);
    StoredBill lockBill(long id);
    BillingRow row(long id);
    List<BillingRow> list(BillingFilter filter);
    List<BillingRow> export(BillingFilter filter);
    long count(BillingFilter filter);
    List<DetailLine> details(long id);
    List<Receipt> receipts(long id);
    List<BillingRow> amendments(long id);
    List<History> history(long id);
    Long insert(BillWrite write);
    int update(BillWrite write);
    void deleteDetails(long id);
    void moveDetails(DetailMonth write);
    void insertDetail(DetailWrite write);
    void audit(Audit audit);
    void downloadAccess(String description);
}
