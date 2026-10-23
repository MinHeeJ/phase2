package com.sicost.uploadsops;

import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import static com.sicost.uploadsops.UploadModels.*;

@Mapper
public interface UploadMapper {
    List<Code> codes();
    List<Code> mappings();
    String existing(String bizNo);
    Original original(Long id);
    List<StoredIssue> issues(Long id);
    Long insertLedger(Ledger write);
    void insertIssue(IssueWrite write);
    Long nextPartnerId();
    void insertPartner(PartnerWrite write);
    void history(History write);
    void access(Access write);
}
