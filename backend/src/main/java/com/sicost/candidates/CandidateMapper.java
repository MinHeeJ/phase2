package com.sicost.candidates;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.candidates.CandidateModels.*;

@Mapper
public interface CandidateMapper {
    List<CandidateRow> list(CandidateQuery query);
    CandidateKpi kpi(CandidateQuery query);
    CandidateRow row(long id);
    Long lock(long id);
    List<String> skills(long id);
    List<CandidateContact> contacts(long id);
    List<CandidateSlotSource> slots(long id);
    boolean activeCode(CandidateCode code);
    boolean activeVendor(long id);
    long nextId();
    Long insert(CandidateInsert input);
    void update(CandidateUpdate input);
    void insertSkill(CandidateChild child);
    void deleteSkills(long id);
    void appendHistory(CandidateHistory history);
}
