package com.sicost.withdrawalsops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.withdrawalsops.NextSlotModels.*;

@Mapper
public interface NextSlotMapper {
    List<Profile> profiles();
    List<Need> needs();
    List<String> grades();
    List<String> jobs(long devId);
    List<TemporaryRow> temporary(long devId);
}
