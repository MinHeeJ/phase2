package com.sicost.projectsops;

import com.sicost.apiops.ProjectBoardModels.BoardSource;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.projectsops.ProjectProgressModels.*;

@Mapper
public interface ProjectProgressMapper {
    ProjectState state(long id);
    BoardSource source(ProgressRead read);
    List<MemoSource> memos(long id);
    List<ProjectIssue> issues(long id);
}
