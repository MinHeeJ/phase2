package com.sicost.costallocation;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.costallocation.CostAllocationModels.*;

@Mapper
public interface CostAllocationMapper {
    String monthStatus(String month);
    boolean projectExists(long projectId);
    List<Person> people(String month);
    List<Assignment> assignments(String month);
    List<Expense> expenses(String month);
    List<Turnkey> turnkey(String month);
    List<AllocationRow> snapshots(String month);
    List<String> projectMonths(ProjectPeriod query);
    boolean expectedCost(Query query);
}
