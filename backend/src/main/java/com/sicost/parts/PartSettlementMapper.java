package com.sicost.parts;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.parts.PartSettlementModels.*;

@Mapper
public interface PartSettlementMapper {
    List<Part> parts();
    Part part(long id);
    Long lockPart(long id);
    Long lockSettlement(Scope scope);
    String monthStatus(String month);
    String lockMonth(String month);
    Settlement settlement(Scope scope);
    List<Standard> standards(long id);
    List<Person> people(Scope scope);
    String projectNames(PersonMonth scope);
    List<Item> details(long id);
    List<History> history(long id);
    long saveCard(CardWrite write);
    int snapshot(SnapshotWrite write);
    int transition(Transition write);
    void deleteDetails(long id);
    void insertDetail(ItemWrite write);
    void historyWrite(Transition write);
    void audit(Audit write);
    void access(Access write);
}
