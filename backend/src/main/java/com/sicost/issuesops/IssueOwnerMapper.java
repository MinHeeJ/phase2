package com.sicost.issuesops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.issuesops.IssueOwnerModels.*;

@Mapper
public interface IssueOwnerMapper {
    boolean projectExists(long projectId);
    List<IssueOwnerOption> options(IssueOwnerQuery query);
}
