package com.sicost.staffplans;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.staffplans.ProjectStaffPlanModels.*;

@Mapper
public interface ProjectStaffPlanMapper {
    StaffProject project(long id);
    List<StaffPlan> plans(long id);
    List<ActualMM> actual(long id);
    boolean activeCode(StaffCode code);
    void deleteManual(long id);
    void insert(StaffWrite write);
    void history(StaffHistory history);
}
