package com.sicost.uploadsops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.uploadsops.UploadModels.*;

@Mapper
public interface UploadMapper {
    List<Code> codes();
    List<Code> mappings();
    String existingPartner(String businessNo);
    Original original(long id);
    List<Issue> issues(long id);
    long insertLedger(Ledger ledger);
    void insertIssue(IssueWrite write);
    long reservePartnerId();
    void insertPartner(PartnerWrite write);
    void insertHistory(HistoryWrite write);
    void insertAccess(AccessWrite write);
}
