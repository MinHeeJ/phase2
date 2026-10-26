package com.sicost.projects;

import com.sicost.common.validation.ProjectPhases;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.projects.ProjectModels.*;

@Mapper
public interface ProjectMapper {
    List<ProjectListItem> list(ProjectQuery query);
    long count(ProjectQuery query);
    long unassignedCount(ProjectQuery query);
    ProjectRow row(ProjectRead read);
    Long resolveNumber(String number);
    List<String> skills(long id);
    boolean activeCode(ProjectCode code);
    boolean activeCustomer(long id);
    boolean activePart(long id);
    boolean activeDeveloper(long id);
    boolean activeProject(long id);
    Long latestPart(long id);
    boolean typeLocked(long id);
    boolean confirmed(long id);
    BigDecimal budgetRate(String month);
    List<ProjectPhases.Definition> phases(String group);
    void numberLock();
    int maximumSuffix(String prefix);
    Long insert(ProjectWrite write);
    void update(ProjectUpdate update);
    void insertSkill(ProjectChild child);
    void deleteSkills(long id);
    void insertPart(ProjectPart part);
    void insertPhase(ProjectPhase phase);
    void appendHistory(ProjectHistory history);
}
