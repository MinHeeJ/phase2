package com.sicost.salespartsops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.salespartsops.SalesPartModels.*;

@Mapper
public interface SalesPartManagementMapper {
    Part lock(Read query);
    Part part(Read query);
    List<Standard> standards(Read query);
    String lastClosed();
    List<Option> options();
    boolean nameExists(ValueWrite write);
    void rename(ValueWrite write);
    void use(ValueWrite write);
    void deleteScheduled(PeriodWrite write);
    void closePrevious(PeriodWrite write);
    Long insertStandard(StandardWrite write);
    void appendHistory(History entry);
    List<ExportRow> exportRows(ExportQuery query);
    long existingIds(List<Long> ids);
    void appendAccess(Access entry);
}
