package com.sicost.people;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.people.PeopleModels.*;

@Mapper
public interface PeopleMapper {
    List<PersonMonth> people(Period period);
    List<ProjectLink> projects(Period period);
    boolean personExists(long devId);
    void download(Download download);
}
