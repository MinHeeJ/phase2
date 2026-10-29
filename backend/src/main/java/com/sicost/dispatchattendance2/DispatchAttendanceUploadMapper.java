package com.sicost.dispatchattendance2;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

@Mapper
public interface DispatchAttendanceUploadMapper {
    long nextUploadId();
    Project project(long id);
    Project lockProject(long id);
    String monthStatus(String month);
    List<Source> sources(Scope scope);
    boolean confirmedUse(Scope scope);
    List<Stored> lockAttendance(Scope scope);
    String uploadStatus(long id);
    void insertReview(ReviewWrite write);
    void deleteAttendance(Scope scope);
    long insertAttendance(AttendanceWrite write);
    void audit(Audit audit);
    void access(com.sicost.uploadsops.UploadModels.UploadAccess access);
}
