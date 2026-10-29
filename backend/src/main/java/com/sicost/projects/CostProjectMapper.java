package com.sicost.projects;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.projects.CostProjectModels.*;

@Mapper
public interface CostProjectMapper {
    List<Source> sources(Query query);
    Source source(Period period);
    List<PersonName> people(long id);
    BigDecimal costRate(String month);
    Long referenceRevenue(Period period);
    void access(Access access);
}
