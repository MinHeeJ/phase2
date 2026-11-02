package com.sicost.businessplan2;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.businessplan2.BusinessPlanUploadModels.*;

@Mapper
public interface BusinessPlanUploadMapper {
    long nextUploadId();
    List<Long> parts(String label);
    List<Plan> plans(String year);
    List<String> closedMonths(String year);
    String uploadStatus(long id);
    void lockScope();
    void saveReview(ReviewWrite write);
    void deleteOpen(Plan row);
    long replace(PlanWrite write);
    void audit(Audit audit);
}
