package com.sicost.salesparts;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.sicost.salesparts.SalesPartModels.*;

@Mapper
public interface SalesPartMapper {
    List<PartRow> parts(SalesPartQuery query);
    long partCount();
    PartRow part(@Param("id") long id,@Param("today") LocalDate today);
    List<SalesPartStandard> standards(@Param("id") long id,@Param("month") String month);
    String lastClosed();
    boolean nameExists(String name);
    Long insertPart(PartWrite write);
    Long insertStandard(StandardWrite write);
    void appendHistory(PartHistory history);
}
