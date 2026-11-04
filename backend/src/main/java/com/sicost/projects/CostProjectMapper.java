package com.sicost.projects;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.sicost.projects.CostProjectModels.*;

@Mapper
public interface CostProjectMapper {
    List<Source> sources(Query query);
    Source source(long id);
    List<Source> batchSources(Batch batch);
    List<ProjectPlan> batchPlans(Batch batch);
    List<ProjectLabor> batchLabor(Batch batch);
    List<ProjectReference> batchReference(Batch batch);
    List<Plan> plans(Period period);
    List<Labor> labor(Period period);
    List<Reference> reference(Period period);
    long headcount(Period period);
    BigDecimal parameter(@Param("key") String key, @Param("month") String month);
    void download(Download download);
}
