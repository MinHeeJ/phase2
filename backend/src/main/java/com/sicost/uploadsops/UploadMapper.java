package com.sicost.uploadsops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.uploadsops.UploadModels.*;

@Mapper
public interface UploadMapper {
    List<UploadCode> codes();
    List<UploadMapping> mappings();
    String duplicateName(String biz);
    UploadSource source(long id);
    List<UploadIssue> issues(long id);
    long insertUpload(UploadWrite write);
    void insertIssue(IssueWrite write);
    long nextPartnerId();
    long insertPartner(PartnerImport write);
    void appendHistory(ImportHistory write);
    void access(UploadAccess write);
}
