package com.sicost.developersops;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.developersops.DeveloperLifecycleModels.*;

@Mapper
public interface DeveloperLifecycleMapper {
    DeveloperState state(long id);
    long currentAssignments(DeveloperDate query);
    void changeState(DeveloperStateWrite write);
    void audit(DeveloperAudit audit);
    List<DeveloperOption> options(DeveloperOptionQuery query);
    List<DeveloperAssignment> assignments(DeveloperReadQuery query);
    long assignmentCount(DeveloperReadQuery query);
    List<DeveloperChange> history(DeveloperReadQuery query);
    long historyCount(DeveloperReadQuery query);
    List<DeveloperMonthlyCost> monthlyCosts(DeveloperReadQuery query);
    long monthlyCostCount(DeveloperReadQuery query);
    List<Map<String,Object>> exportRows(DeveloperExportQuery query);
    Map<String,Object> detail(DeveloperDate query);
    List<String> subJobs(long id);
    List<String> skills(long id);
    DeveloperSalary latestSalary(long id);
}
