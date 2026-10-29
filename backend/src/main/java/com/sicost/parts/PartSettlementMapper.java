package com.sicost.parts;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.parts.PartSettlementModels.*;

@Mapper
public interface PartSettlementMapper {
    List<Part> parts();
    Part part(long id);
    Long lockPart(long id);
    String monthStatus(String month);
    Header header(Key key);
    Header lockHeader(Key key);
    void ensureDraft(Key key);
    Standard standard(Key key);
    List<Standard> standards(long id);
    List<Salary> salaries(Key key);
    long missingSalary(Key key);
    List<Person> details(long id);
    List<History> history(long id);
    String note(long id);
    int card(CardWrite write);
    int confirm(Confirmation write);
    int cancel(Transition write);
    void clearDetails(long id);
    void insertDetail(DetailWrite write);
    void insertHistory(HistoryWrite write);
    void audit(Audit write);
    void access(Access write);
}
