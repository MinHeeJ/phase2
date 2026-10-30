package com.sicost.cashflow;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.cashflow.CashFlowModels.*;

@Mapper
public interface CashFlowMapper {
    Balance initial();
    List<Balance> balances();
    String monthStatus(String month);
    void lockOpening();
    void saveOpening(OpeningWrite write);
    List<Inflow> inflows();
    Inflow inflow(long id);
    long insertInflow(InflowWrite write);
    int updateInflow(InflowWrite write);
    void audit(Audit audit);
    List<Event> events();
}
