package com.sicost.workforce;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.workforce.WorkforceForecastModels.*;

@Mapper
public interface WorkforceForecastMapper {
    List<Source> forecast(Read input);
    long candidateCount(CandidateMatch input);
    BigDecimal utilizationTarget(String month);
}
