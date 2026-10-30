package com.sicost.businessplan;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.businessplan.BusinessPlanModels.*;

@Mapper
public interface BusinessPlanMapper {
    List<Plan> list(int year);
    List<Part> parts();
    List<String> closedMonths(int year);
    void lockYear(int year);
    List<String> lockMonths(int year);
    long upsert(PlanWrite write);
    void audit(PlanAudit audit);
    List<Attribution> attributions(int year);
    ClosedActual closedActual(String month);
}
