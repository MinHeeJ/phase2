package com.sicost.projectsops;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.projectsops.ProjectLifecycleModels.*;

@Mapper
public interface ProjectLifecycleMapper {
    ProjectState state(long id);
    ProjectState lockState(long id);
    ProjectUsage usage(long id);
    Map<String,Object> detail(ProjectRead query);
    List<String> skills(long id);
    List<ProjectOption> options(String q);
    List<ProjectAssignment> assignments(long id);
    List<ProjectHistoryEntry> history(String id);
    List<Map<String,Object>> exportRows(ProjectExportQuery query);
    void changeState(ProjectStateWrite write);
    void audit(ProjectAudit audit);
}
