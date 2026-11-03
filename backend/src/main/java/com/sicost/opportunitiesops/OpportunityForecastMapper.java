package com.sicost.opportunitiesops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.opportunitiesops.OpportunityForecastModels.*;

@Mapper
public interface OpportunityForecastMapper {
    List<Developer> developers(String month);
    List<Leave> leaves();
    List<Assignment> assignments();
    List<AssignedMonth> assignmentMonths();
    List<Project> projects();
    List<ProjectPlan> plans();
    List<Opportunity> opportunities(Query query);
    List<Demand> needs();
    List<Temporary> temporary();
    long candidates(CandidateFilter filter);
    boolean code(CandidateFilter filter);
}
