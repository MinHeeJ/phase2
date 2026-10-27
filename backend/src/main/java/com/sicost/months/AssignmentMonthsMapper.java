package com.sicost.months;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.months.AssignmentMonthsModels.*;

@Mapper
public interface AssignmentMonthsMapper {
    AssignmentState assignment(long id);
    List<MonthRow> months(long id);
    List<Warning> warnings(long id);
    boolean overlaps(long id);
    int update(MonthWrite write);
    void audit(MonthAudit audit);
}
