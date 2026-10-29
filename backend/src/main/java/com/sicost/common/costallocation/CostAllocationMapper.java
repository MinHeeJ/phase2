package com.sicost.common.costallocation;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.common.costallocation.CostAllocationModels.*;

@Mapper
public interface CostAllocationMapper {
    String monthStatus(String month);
    Long project(long id);
    List<Person> people(String month);
    List<Assignment> assignments(String month);
    List<Expense> expenses(String month);
    List<Allocation> snapshots(Scope scope);
    long missingSnapshots(Scope scope);
    List<String> projectMonths(Scope scope);
}
