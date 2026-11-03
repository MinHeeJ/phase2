package com.sicost.apiops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.apiops.ClosingQueryModels.*;

@Mapper
public interface ClosingQueryMapper {
    State state(String month);
    List<StoredCheck> checks(String month);
    List<StoredCheck> frozenChecks(String month);
    String latestClosedMonth();
    long activeReferences(String month);
    String snapshotEvidence(String month);
    String closeManifest(String month);
}
