package com.sicost.allocations;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.allocations.ProjectAllocationModels.*;

@Mapper
public interface ProjectAllocationMapper {
    AllocationProject project(long id);
    String activeStage(long id);
    String reallocation(long id);
    List<AllocationStoredLine> lines(AllocationScope scope);
    List<AllocationMonth> months(long id);
    boolean activeDeveloper(long id);
    boolean activeCode(AllocationCode code);
    void deleteMonths(AllocationScope scope);
    void deleteLines(AllocationScope scope);
    Long insertLine(AllocationWrite write);
    void insertMonth(AllocationMonthWrite write);
    void clearReallocation(long id);
    void audit(AllocationAudit audit);
}
