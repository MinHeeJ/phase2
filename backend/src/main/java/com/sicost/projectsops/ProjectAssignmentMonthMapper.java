package com.sicost.projectsops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.projectsops.ProjectAssignmentMonthModels.*;

@Mapper
public interface ProjectAssignmentMonthMapper {
    boolean projectExists(long id);
    List<StoredMonth> months(Query query);
}
