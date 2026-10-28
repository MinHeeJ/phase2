package com.sicost.dispatchattendance2;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

@Mapper
public interface DispatchAttendanceUploadMapper {
    Project project(long id);
    Project lockProject(long id);
    String monthStatus(String month);
    boolean confirmedUsage(Scope scope);
    List<Assignment> assignments(Scope scope);
    List<Attendance> attendance(Scope scope);
    long nextUploadId();
    void insertReview(ReviewWrite write);
    void deleteAttendance(Scope scope);
    Long insertAttendance(AttendanceWrite write);
    void audit(Audit write);
    void access(com.sicost.uploadsops.UploadModels.UploadAccess write);
}
