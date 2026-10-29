package com.sicost.dispatchattendance;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

@Mapper
public interface DispatchAttendanceMapper {
    Project project(long projectId);
    List<Source> list(Scope scope);
    List<Input> existing(Key key);
    boolean billed(Key key);
    Long upsert(Write write);
}
