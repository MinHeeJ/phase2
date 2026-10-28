package com.sicost.dispatchattendance;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

@Mapper
public interface DispatchAttendanceMapper {
    Project project(long projectId);
    Project lockProject(long projectId);
    String monthStatus(String month);
    List<Source> sources(Scope scope);
    Input existing(Write write);
    boolean billed(Write write);
    Long upsert(Write write);
    void audit(Audit audit);
}
