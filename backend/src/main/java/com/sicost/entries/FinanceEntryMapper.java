package com.sicost.entries;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.entries.FinanceEntryModels.*;

@Mapper
public interface FinanceEntryMapper {
    List<FinanceEntry> list(String month);
    FinanceEntry row(long id);
    FinanceEntry lockRow(long id);
    List<Long> copiedSources(String month);
    List<EntryMonth> months(String year);
    String monthStatus(String month);
    long insert(EntryWrite write);
    int update(EntryWrite write);
    int delete(EntryRemoval removal);
    void audit(EntryAudit audit);
}
