package com.sicost.assignmentsops;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.assignmentsops.AssignmentOperationModels.*;

@Mapper
public interface AssignmentOperationMapper {
    Project project(long id);
    Developer developer(long id);
    Row row(ReadKey key);
    List<Row> exportRows(ExportQuery query);
    List<Month> months(long id);
    List<Rate> rates(long id);
    List<Change> changes(long id);
    List<String> closedMonths();
    boolean onLeave(PersonPeriod period);
    boolean overlaps(PersonPeriod period);
    BigDecimal personTotal(PersonMonth month);
    boolean activeCode(CodeKey code);
    String codeName(CodeKey code);
    String inUse(long id);
    int extendCount(long id);
    long nextId();
    void cancel(ChanceWrite write);
    void chance(ChanceWrite write);
    void shorten(EndWrite write);
    void insertReplacement(NewAssignment write);
    void deleteOutsideOpen(EndWrite write);
    void deleteOpen(long id);
    void saveMonth(MonthWrite write);
    void insertRate(RateWrite write);
    long insertHistory(HistoryWrite write);
}
