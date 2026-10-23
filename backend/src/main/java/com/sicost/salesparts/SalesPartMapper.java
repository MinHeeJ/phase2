package com.sicost.salesparts;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.sicost.salesparts.SalesPartModels.*;

@Mapper
public interface SalesPartMapper {
    List<PartRow> list(SalesPartQuery query);
    long count();
    PartRow part(@Param("id") String id,@Param("query") SalesPartQuery query);
    List<SalesPartStandard> standards(@Param("id") String id,@Param("month") String month);
    String lastClosed();
    boolean nameExists(String name);
    String insertPart(PartWrite write);
    String insertStandard(StandardWrite write);
    void appendHistory(PartHistory history);
}
