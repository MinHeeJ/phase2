package com.sicost.aiops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.sicost.aiops.RecommendationOptionsModels.*;

@Mapper
public interface RecommendationOptionsMapper {
    List<SourceRow> sources(@Param("month") String month, @Param("search") String search);
    List<String> sourceSkills(SourceReference reference);
    List<ExitOption> developers(ExitQuery query);
    boolean developerExists(long devId);
    List<String> codes(String group);
}
