package com.sicost.dispatchattendance2;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

@Mapper
public interface DispatchAttendanceUploadMapper {
    Project project(long id);
    List<Source> sources(Scope scope);
    String monthStatus(String month);
    boolean confirmedUsage(Scope scope);
    List<Attendance> lockAttendance(Scope scope);
    void deleteAttendance(Scope scope);
    long insertAttendance(AttendanceWrite write);
    void insertUpload(UploadWrite write);
    void audit(Audit write);
    void access(com.sicost.uploadsops.UploadModels.UploadAccess write);
}
