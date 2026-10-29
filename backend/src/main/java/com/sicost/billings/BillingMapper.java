package com.sicost.billings;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.billings.BillingModels.*;

@Mapper
public interface BillingMapper {
    List<BillingRow> list(Query query);
    long count(Query query);
    BillingRow row(long id);
    Header header(long id);
    Project project(long id);
    Stage stage(long id);
    List<Header> duplicates(Duplicate key);
    List<Source> sources(SourceKey key);
    List<DetailLine> details(long id);
    List<Receipt> receipts(long id);
    List<BillingRow> amendments(long id);
    List<History> history(long id);
    Long insert(Write write);
    int update(Write write);
    void insertDetail(DetailWrite write);
    void deleteDetails(long id);
}
