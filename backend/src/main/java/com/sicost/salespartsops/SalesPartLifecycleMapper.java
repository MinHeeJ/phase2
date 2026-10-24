package com.sicost.salespartsops;

import com.sicost.salesparts.SalesPartModels.PartRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.sicost.salespartsops.SalesPartLifecycleModels.*;

@Mapper
public interface SalesPartLifecycleMapper {
    PartRow lock(@Param("id") long id,@Param("today") LocalDate today);
    List<SalesPartOption> options();
    boolean nameExists(@Param("id") long id,@Param("name") String name);
    void rename(PartValueWrite write);
    void state(PartValueWrite write);
    void deleteScheduled(StandardReplacement write);
    void closePrevious(StandardReplacement write);
    Long insertStandard(StandardReplacement write);
    void audit(SalesPartAudit audit);
    List<Map<String,Object>> exportRows(SalesPartExportQuery query);
    void access(SalesPartAccess access);
}
