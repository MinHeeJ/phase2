package com.sicost.candidatesops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.candidatesops.CandidateContactModels.*;

@Mapper
public interface CandidateContactMapper {
    CandidateRow lock(long id);
    CandidateRow row(long id);
    List<String> skills(long id);
    List<Contact> contacts(long id);
    List<SlotRow> matching(long id);
    void append(ContactWrite write);
    int refreshContact(ContactWrite write);
    int convert(ConversionWrite write);
    void audit(CandidateAudit audit);
    long candidateCount(CandidateCount query);
}
