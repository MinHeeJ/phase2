package com.sicost.partnersops;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.partnersops.PartnerActivityModels.*;

@Mapper
public interface PartnerActivityMapper {
    PartnerRecord partner(String id);
    PartnerRecord lockPartner(String id);
    List<PartnerOption> options(String kind);
    List<PartnerAssignment> assignments(PartnerDateQuery query);
    List<PartnerChange> history(String id);
    PartnerCounts counts(PartnerDateQuery query);
    int state(PartnerStateWrite write);
    void appendHistory(PartnerHistoryWrite write);
    List<Map<String,Object>> exportRows(PartnerExportQuery query);
    void download(PartnerDownloadWrite write);
}
