package com.sicost.us4;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.us4.PlanComparisonModels.*;

@Mapper
public interface PlanComparisonMapper {
    List<Source> annual(Query query);
    List<ProjectOption> projects(Query query);
    BigDecimal tolerance(String month);
}
