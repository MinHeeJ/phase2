package com.sicost.apiops;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.apiops.ProjectBoardModels.*;

@Mapper
public interface ProjectBoardMapper {
    Long allocationProject(long id);
    String allocationMonthStatus(String month);
    List<String> allocationMonths(CostQuery query);
    List<com.sicost.common.costs.CostAllocationModels.Salary> allocationSalaries(String month);
    List<com.sicost.common.costs.CostAllocationModels.Assignment> allocationAssignments(String month);
    List<com.sicost.common.costs.CostAllocationModels.Expense> allocationExpenses(String month);
    List<com.sicost.common.costs.CostAllocationModels.Turnkey> allocationTurnkey(String month);
    List<com.sicost.common.costs.CostAllocationModels.Allocation> allocationSnapshots(String month);
    List<OperatingParam> params(String month);
    List<BoardSource> projects(LocalDate today);
    List<Phase> phases(long id);
    default List<CostMonth> costs(CostQuery query) {
        return new com.sicost.common.costs.CostAllocationService(this).costs(query.id(), null, query.month());
    }
}
