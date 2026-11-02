package com.sicost.adjustments;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.adjustments.AdjustmentModels.*;

@Mapper
public interface AdjustmentMapper {
    void lockMonths();
    String monthStatus(String month);
    List<Row> list(Query query);
    long count(Query query);
    Row row(long id);
    Row lockRow(long id);
    boolean project(long id);
    boolean developer(long id);
    long insert(Write write);
    int cancel(Cancel cancel);
    void audit(Audit audit);
    void access(Audit audit);
}
