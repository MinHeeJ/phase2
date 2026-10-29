package com.sicost.people;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.people.CostPeopleModels.*;

@Mapper
public interface CostPeopleMapper {
    List<Person> people(String month);
    List<Payroll> payroll(String month);
    List<Project> projects(String month);
    Long person(long id);
    void download(Access access);
}
