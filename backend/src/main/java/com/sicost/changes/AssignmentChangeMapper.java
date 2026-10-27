package com.sicost.changes;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.changes.AssignmentChangeModels.*;

@Mapper
public interface AssignmentChangeMapper {
    State state(long id);
    Header header(ReadKey key);
    String remark(long id);
    List<ChangeRow> list(ChangeFilter filter);
    ChangeRow row(ChangeKey key);
    List<RateRow> rates(long id);
    List<MonthRow> months(long id);
    List<String> closedMonths();
    String codeName(CodeKey key);
    Long developer(long id);
    int overlap(PeriodWrite write);
    List<Warning> warnings(long id);
    LocalDate firstStart(long id);
    Integer dispatchMonths(String month);
    void period(PeriodWrite write);
    void chance(ChanceWrite write);
    void rate(RateWrite write);
    void moveRate(RateMove move);
    void deleteOutsideMonths(long id);
    void month(MonthWrite write);
    Long insert(HistoryWrite write);
    int markFixed(ChangeKey key);
}
