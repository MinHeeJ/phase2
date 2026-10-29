package com.sicost.billings;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.billings.BillingModels.*;

@Mapper
public interface BillingMapper {
    List<BillingRow> list(BillingQuery query);
    long count(BillingQuery query);
    BillingRow row(long id);
    State state(long id);
    State lockBill(long id);
    Project lockProject(long id);
    Stage stage(StageKey key);
    String monthStatus(String month);
    BigDecimal vatRate(String month);
    void numberLock(String prefix);
    long maximumSuffix(String prefix);
    int maximumOrdinal(DuplicateScope scope);
    Long existing(DuplicateScope scope);
    List<Source> sources(SourceScope scope);
    List<DetailLine> details(long id);
    List<Receipt> receipts(long id);
    List<History> history(long id);
    List<BillingRow> amendments(long id);
    long insert(Write write);
    int update(Write write);
    void insertDetail(DetailWrite write);
    int updateDetail(DetailWrite write);
    void deleteDetails(long id);
    void audit(Audit audit);
}
