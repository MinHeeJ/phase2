package com.sicost.developers;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.developers.DeveloperModels.*;

@Mapper
public interface DeveloperMapper {
    List<DeveloperListItem> list(DeveloperQuery query);
    long count(DeveloperQuery query);
    DeveloperListItem summary(DeveloperRead query);
    DeveloperRow row(long id);
    List<String> subJobs(long id);
    List<String> skills(long id);
    LatestSalary latestSalary(long id);
    List<DeveloperCodeName> codeNames();
    String partName(long id);
    boolean activeCode(DeveloperCode code);
    boolean activePartner(long id);
    boolean activePart(long id);
    boolean duplicateNumber(String no);
    long nextId();
    Long insert(DeveloperWrite write);
    void update(DeveloperWrite write);
    void deleteJobs(long id);
    void deleteSkills(long id);
    void insertJob(DeveloperChild child);
    void insertSkill(DeveloperChild child);
    void appendHistory(DeveloperHistory history);
}
