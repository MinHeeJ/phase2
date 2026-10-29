package com.sicost.people;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.people.PeopleCostModels.*;

@Mapper
public interface PeopleCostMapper {
    List<PersonMonth> population(Period period);
    List<AssignmentInfo> assignments(Period period);
    Long person(long id);
    void access(DownloadAccess access);
}
