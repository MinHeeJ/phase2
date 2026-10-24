package com.sicost.workstatus;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.workstatus.WorkStatusModels.*;

@Mapper
public interface WorkStatusMapper {
    boolean developerExists(long devId);
    List<WorkStatusRow> list(WorkStatusQuery query);
    long count(WorkStatusQuery query);
    WorkStatusRow detail(WorkStatusKey key);
    String snapshot(WorkStatusKey key);
    long nextId();
    WorkStatusRow insert(WorkStatusWrite write);
    WorkStatusRow update(WorkStatusWrite write);
    int delete(WorkStatusKey key);
    WorkStatusRow overlap(WorkStatusWrite write);
    boolean resignationExists(WorkStatusWrite write);
    boolean closed(WorkStatusInput input);
    String codeName(String code);
    void resignation(WorkStatusResignation write);
    LocalDate resignationDate(long devId);
    void audit(WorkStatusAudit audit);
}
