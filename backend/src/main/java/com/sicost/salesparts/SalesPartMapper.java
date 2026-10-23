package com.sicost.salesparts;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.salesparts.SalesPartModels.*;

@Mapper
public interface SalesPartMapper {
    List<PartRow> parts(SalesPartQuery query);
    long count();
    PartRow part(PartRead query);
    List<SalesPartStandard> standards(PartRead query);
    String lastClosed();
    void lockParts();
    boolean nameExists(String name);
    Long insertPart(PartWrite write);
    Long insertStandard(StandardWrite write);
    void appendHistory(PartHistory history);
}
