package com.sicost.dispatchattendance;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

@Mapper
public interface DispatchAttendanceMapper {
    Project project(long id);
    Project lockProject(long id);
    String monthStatus(String month);
    List<Assignment> assignments(Scope scope);
    List<Stored> attendance(Scope scope);
    List<Stored> lockAttendance(Scope scope);
    List<Long> confirmedAssignments(Scope scope);
    long upsert(Write write);
    void audit(Audit audit);
}
