package com.sicost.board;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.board.WorkforceBoardModels.*;

@Mapper
public interface WorkforceBoardMapper {
    List<Person> people(Query query);
    List<Leave> leaves(Query query);
    List<MonthlyAssignment> months(Query query);
    List<TemporarySource> temporary(Query query);
    void download(BoardDownload entry);
}
