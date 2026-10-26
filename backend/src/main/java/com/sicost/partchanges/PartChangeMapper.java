package com.sicost.partchanges;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.partchanges.PartChangeModels.*;

@Mapper
public interface PartChangeMapper {
    PartChangeProject project(long id);
    List<PartChange> list(long id);
    String activePartName(long id);
    String lastClosedMonth();
    void upsert(PartChangeWrite write);
    void updateRepresentative(PartChangeWrite write);
    void audit(PartChangeAudit audit);
}
