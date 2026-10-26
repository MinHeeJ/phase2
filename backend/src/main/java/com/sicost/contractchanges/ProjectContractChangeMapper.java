package com.sicost.contractchanges;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.contractchanges.ProjectContractChangeModels.*;

@Mapper
public interface ProjectContractChangeMapper {
    ProjectState project(long id);
    ProjectState lockProject(long id);
    List<ChangeRow> list(long id);
    ChangeRow row(ChangeKey key);
    Long insert(ChangeWrite write);
    void update(ChangeWrite write);
    void delete(ChangeKey key);
    void current(CurrentWrite write);
    void audit(ChangeAudit audit);
}
