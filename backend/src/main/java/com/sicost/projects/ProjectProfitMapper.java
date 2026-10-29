package com.sicost.projects;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.projects.ProjectProfitModels.*;

@Mapper
public interface ProjectProfitMapper {
    List<Source> list(Filter filter);
    Source project(long id);
    List<Plan> plans(Period period);
    List<Forecast> forecast(Period period);
    List<Person> people(long id);
    void download(Download download);
}
