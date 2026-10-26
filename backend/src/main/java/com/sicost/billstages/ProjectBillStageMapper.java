package com.sicost.billstages;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.billstages.ProjectBillStageModels.*;

@Mapper
public interface ProjectBillStageMapper {
    ProjectState project(long id);
    ProjectState lockProject(long id);
    List<Stage> stages(long id);
    void position(StagePosition position);
    void delete(StageKey key);
    void update(StageWrite write);
    Long insert(StageWrite write);
    void history(StageHistory history);
}
