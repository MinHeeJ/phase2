package com.sicost.projectboardops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.projectboardops.ProjectBoardExportModels.*;

@Mapper
public interface ProjectBoardExportMapper {
    List<BoardExportSource> sources(BoardExportQuery query);
    List<BoardExportParam> parameters(String month);
    void access(BoardExportAccess access);
}
