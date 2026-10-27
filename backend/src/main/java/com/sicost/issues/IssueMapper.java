package com.sicost.issues;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.issues.IssueModels.*;

@Mapper
public interface IssueMapper {
    IssueProject lockProject(long id);
    Long parent(long id);
    IssueRow row(long id);
    IssueRow lockIssue(long id);
    List<IssueListRow> list(IssueQuery query);
    long count(IssueQuery query);
    long openCount();
    Long insert(IssueInsert write);
    void update(IssueUpdate write);
    void history(IssueHistory history);
}
