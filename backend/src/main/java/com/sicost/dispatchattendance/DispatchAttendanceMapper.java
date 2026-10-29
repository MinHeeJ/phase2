package com.sicost.dispatchattendance;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

@Mapper
public interface DispatchAttendanceMapper {
    Project project(long id);
    Project lockProject(long id);
    String monthStatus(String month);
    List<Source> sources(Scope scope);
    List<Stored> stored(Scope scope);
    List<Stored> lockAttendance(Scope scope);
    long upsert(AttendanceWrite write);
    void audit(AttendanceAudit audit);
}
