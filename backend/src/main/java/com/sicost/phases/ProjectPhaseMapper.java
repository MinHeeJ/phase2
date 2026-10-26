package com.sicost.phases;

import org.apache.ibatis.annotations.Mapper;
import static com.sicost.phases.ProjectPhaseModels.*;

@Mapper
public interface ProjectPhaseMapper {
    PhaseProject lockProject(long id);
    Phase row(PhaseKey key);
    int complete(PhaseWrite write);
    int uncomplete(PhaseWrite write);
    int period(PhaseWrite write);
    void audit(PhaseAudit audit);
}
