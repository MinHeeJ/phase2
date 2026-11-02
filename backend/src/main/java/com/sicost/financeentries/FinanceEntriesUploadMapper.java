package com.sicost.financeentries;

import com.sicost.uploadsops.UploadModels.*;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.financeentries.FinanceEntriesUploadModels.*;

@Mapper
public interface FinanceEntriesUploadMapper {
    long nextUploadId();
    List<UploadCode> codes();
    List<UploadMapping> mappings();
    String uploadStatus(long id);
    void lockScope();
    void saveReview(ReviewWrite write);
    long insert(EntryWrite write);
    void access(UploadAccess write);
}
