package com.sicost.projectsops;

import java.math.BigDecimal;
import java.time.*;

public final class ProjectLifecycleModels {
    private ProjectLifecycleModels() {}
    public record ProjectState(long id,String useYn) {}
    public record ProjectRead(long id,LocalDate today) {}
    public record ProjectOption(long id,String no,String name,String type,String custName) {}
    public record ProjectAssignment(String name,String job,LocalDate beginDate,LocalDate endDate,BigDecimal assignmentRate,String status) {}
    public record ProjectHistoryEntry(LocalDateTime changedAt,String field,String before,String after,String actor) {}
    public record ProjectUsage(long assignments,long billStages,long allocations,long expenses) {
        public boolean blocked(){return assignments>0||billStages>0||allocations>0||expenses>0;}
        public String message(){return "투입 "+assignments+"건 · 청구 단계 "+billStages+"건 · 배분표 "+allocations+"건 · 직접 경비 "+expenses+"건이 있어 사용 안 함으로 바꿀 수 없습니다.";}
    }
    public record ProjectStateWrite(long id,String useYn,Long actor) {}
    public record ProjectAudit(String id,String type,String before,String after,Long actor) {}
    public record ProjectExportQuery(int year,LocalDate yearStart,LocalDate yearEnd,String type,String status,
        Long partId,boolean unassigned,String q,String order,LocalDate today) {}
}
