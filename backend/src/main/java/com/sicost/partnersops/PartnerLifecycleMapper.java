package com.sicost.partnersops;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.sicost.partnersops.PartnerLifecycleModels.*;

@Mapper
public interface PartnerLifecycleMapper {
    PartnerDetail detail(@Param("id") String id);
    PartnerDetail lock(@Param("id") String id);
    List<PartnerOption> options(@Param("kind") String kind);
    List<PartnerAssignment> assignments(PartnerDateQuery query);
    List<PartnerChange> history(@Param("id") String id);
    PartnerBlock block(PartnerDateQuery query);
    int changeState(PartnerStateWrite write);
    void audit(PartnerAudit entry);
    List<Map<String,Object>> exportRows(PartnerExportQuery query);
    void access(PartnerAccess entry);
}
