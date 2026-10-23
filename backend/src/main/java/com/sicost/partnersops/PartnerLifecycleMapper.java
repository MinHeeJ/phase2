package com.sicost.partnersops;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.partnersops.PartnerLifecycleModels.*;

@Mapper
public interface PartnerLifecycleMapper {
    PartnerState state(Long id);
    PartnerState lock(Long id);
    PartnerCounts counts(PartnerTarget target);
    List<PartnerOption> options(String kind);
    List<PartnerAssignment> assignments(PartnerTarget target);
    List<PartnerChange> history(Long id);
    Map<String,Object> detail(Long id);
    int status(PartnerStatusWrite write);
    void appendHistory(PartnerHistoryWrite write);
    List<Map<String,Object>> exportRows(PartnerExportQuery query);
    void appendAccess(PartnerAccessWrite write);
}
