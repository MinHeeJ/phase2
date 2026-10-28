package com.sicost.billings;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.billings.BillingModels.*;

@Mapper
public interface BillingMapper {
    Project project(long id);
    Project lockProject(long id);
    Stage stage(Scope scope);
    List<Source> sources(Scope scope);
    BigDecimal vatRate(String month);
    String monthStatus(String month);
    void lockNumber(String prefix);
    long nextNumber(String prefix);
    List<State> active(Scope scope);
    State state(long id);
    State lockBill(long id);
    Row row(ReadKey key);
    List<Row> list(Query query);
    long count(Query query);
    List<Line> details(long id);
    List<Receipt> receipts(long id);
    List<Row> amendments(ReadKey key);
    List<History> history(long id);
    Long insert(Write write);
    int update(Write write);
    void insertDetail(DetailWrite write);
    void deleteDetails(long id);
    void moveDetailMonth(Scope scope);
    void overtime(DetailWrite write);
    void audit(Audit audit);
}
