package com.sicost.apiops;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.apiops.ProjectBoardModels.*;

@Mapper
public interface ProjectBoardMapper {
    List<OperatingParam> params(String month);
    List<BoardSource> projects(LocalDate today);
    List<Phase> phases(long id);
    List<CostMonth> costs(CostQuery query);
}
