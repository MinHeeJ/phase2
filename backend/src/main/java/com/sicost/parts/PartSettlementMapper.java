package com.sicost.parts;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.parts.PartSettlementModels.*;

@Mapper
public interface PartSettlementMapper {
    List<Part> parts();
    Part part(long partId);
    Part lockPart(long partId);
    Standard standard(Key key);
    List<Salary> salaries(Key key);
    Header header(Key key);
    Header lockHeader(Key key);
    String monthStatus(String month);
    String lockMonth(String month);
    List<Person> details(long stlmId);
    List<History> history(long stlmId);
    Long insertDraft(Key key);
    int card(CardWrite write);
    int confirm(Snapshot snapshot);
    int cancel(CancelWrite write);
    void deleteDetails(long stlmId);
    void insertDetail(DetailWrite write);
    void audit(Audit audit);
    void event(Event event);
    void access(Event event);
}
