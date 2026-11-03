package com.sicost.tempassignments;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.tempassignments.TempAssignmentModels.*;

@Mapper
public interface TempAssignmentMapper {
    List<Row> list(Query query);
    long count(Query query);
    Row row(long id);
    Long opportunityId(long needId);
    String lockOpportunity(long id);
    Need lockNeed(long id);
    Long lockDeveloper(long id);
    boolean duplicate(Write input);
    BigDecimal recommendationScore(Write input);
    Long insert(Insert insert);
    int release(Release release);
    List<String> warnings(Row row);
    void appendHistory(History history);
}
