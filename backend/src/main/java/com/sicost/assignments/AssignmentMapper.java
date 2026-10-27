package com.sicost.assignments;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.assignments.AssignmentModels.*;

@Mapper
public interface AssignmentMapper {
    List<Row> list(Query query);
    Row row(ReadKey key);
    Project project(long id);
    Developer developer(long id);
    boolean activeCode(CodeKey key);
    List<String> closedMonths();
    String overlap(CreateInput input);
    long nextId();
    void insert(Write write);
    void rate(RateWrite write);
    void month(MonthWrite write);
    long start(StartWrite write);
    Change change(long id);
    List<Rate> rates(long id);
    List<Month> months(long id);
}
