package com.sicost.priorprofit;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import com.sicost.uploadsops.UploadModels.*;
import static com.sicost.priorprofit.PriorProfitUploadModels.*;

@Mapper
public interface PriorProfitUploadMapper {
    long nextUploadId();
    List<UploadCode> codes();
    List<UploadMapping> mappings();
    boolean hasClose();
    List<Snapshot> snapshots();
    List<Detail> details();
    String uploadStatus(long id);
    void lockScope();
    void saveReview(ReviewWrite write);
    void deleteImports();
    void insertSnapshot(SnapshotWrite write);
    void insertDetail(Detail row);
    void audit(Audit audit);
    void access(UploadAccess access);
}
