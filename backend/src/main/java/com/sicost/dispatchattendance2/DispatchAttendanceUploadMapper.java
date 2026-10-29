package com.sicost.dispatchattendance2;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

@Mapper
public interface DispatchAttendanceUploadMapper {
    Project project(long projectId);
    List<Assignment> assignments(Scope scope);
    boolean billed(Scope scope);
    List<Attendance> existing(Scope scope);
    void delete(Scope scope);
    Long insert(AttendanceWrite write);
    void review(ReviewWrite write);
}
