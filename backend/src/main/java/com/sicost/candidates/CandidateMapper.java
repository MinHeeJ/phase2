package com.sicost.candidates;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.candidates.CandidateModels.*;

@Mapper
public interface CandidateMapper {
    long nextId();
    CandidateRow row(long id);
    CandidateRow lock(long id);
    List<CandidateRow> list(CandidateQuery query);
    long count(CandidateQuery query);
    CandidateKpi kpi(CandidateQuery query);
    List<String> skills(long id);
    List<CandidateContact> contacts(long id);
    List<SlotSource> slots(long id);
    boolean activeCode(Code code);
    boolean activePartner(long id);
    void insert(CandidateWrite write);
    void update(CandidateWrite write);
    void deleteSkills(long id);
    void insertSkill(CandidateChild child);
    void appendContact(ContactWrite write);
    void projectLatestContact(long id);
    void convert(Conversion conversion);
    void linkDeveloper(Conversion conversion);
    void audit(CandidateAudit audit);
}
