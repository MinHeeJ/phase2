package com.sicost.memos;

import org.apache.ibatis.annotations.Mapper;
import static com.sicost.memos.ProjectMemoModels.*;

@Mapper
public interface ProjectMemoMapper {
    ProjectState lockProject(long id);
    MemoRow today(MemoDate date);
    MemoRow latest(long id);
    MemoRow row(MemoKey key);
    Long insert(MemoWrite write);
    void update(MemoWrite write);
}
