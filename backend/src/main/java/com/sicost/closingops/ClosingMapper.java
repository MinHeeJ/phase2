package com.sicost.closingops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import com.sicost.costallocation.CostAllocationModels.AllocationRow;
import com.sicost.profit.MonthlyProfitModels.Detail;
import static com.sicost.closingops.ClosingModels.*;

@Mapper
public interface ClosingMapper {
    void lockMonths();
    void ensureMonth(String month);
    String status(String month);
    List<String> closedMonths();
    String profitSource(String month);
    long unlinkedTaxPayments(String month);
    String actor(Long id);
    List<Check> sourceChecks(String month);
    void saveChecks(CheckRun run);
    void archiveChecks(CheckRun run);
    List<HistoryRow> history(HistoryQuery query);
    long historyCount(HistoryQuery query);
    StoredPreview preview(String month);
    List<Detail> sgaDetails(String month);
    void allocation(AllocationRow row);
    void profit(SnapshotWrite write);
    void detail(DetailWrite write);
    void tax(SnapshotWrite write);
    void cash(SnapshotWrite write);
    int transition(Transition transition);
    void historyWrite(Transition transition);
    void audit(Audit audit);
    void access(Audit audit);
    void deleteAllocations(String month);
    void deleteProfit(String month);
    void deleteTax(String month);
    void clearCash(String month);
}
