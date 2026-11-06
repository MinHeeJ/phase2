package com.sicost.opportunitiesops;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.sicost.common.security.ScopePredicate;
import static com.sicost.opportunitiesops.OpportunityLifecycleModels.*;

@Mapper
public interface OpportunityLifecycleMapper {
    Row lock(long id);
    Row lockScoped(@Param("id") long id,@Param("scope") ScopePredicate scope);
    java.util.Map<String,Object> auditValues(long id);
    Row row(long id);
    List<Need> needs(long id);
    List<ApprovalHistory> history(long id);
    List<CostSource> costSources(java.time.LocalDate today);
    BigDecimal approvalRate(String month);
    void change(Change change);
    void audit(Audit audit);
    void approval(ApprovalWrite write);
    List<Long> lockNeeds(long id);
    List<String> closedMonths();
    void release(Change change);
    List<Temp> temporary(long id);
    void convert(Conversion conversion);
    void link(Link link);
    void plan(Plan plan);
    List<ExportRow> exportRows(ExportQuery query);
    void download(Download download);
}
