package com.sicost.partnersops;

import com.sicost.common.security.Sensitive;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

public final class PartnerLifecycleModels {
    private PartnerLifecycleModels() {}
    public record PartnerOption(String id, String no, String name) {}
    public record PartnerState(Long id, String name, String kind, String useYn) {}
    public record PartnerCounts(long projectCount, long peopleCount) {}
    public record PartnerDisableBlocked(String id, String name, long projectCount, long peopleCount, String reason) {}
    public record PartnerDisableResult(List<String> disabledIds, List<PartnerDisableBlocked> blocked) {}
    public record PartnerAssignment(String projectName, String developerName, String affiliation, String jobCode, String jobName, BigDecimal assignmentRate, LocalDate endDate) {}
    public record PartnerChange(LocalDateTime changedAt, String column, String itemName, String before, String after, String changeType, String actorName) {}
    public record PartnerTarget(Long id, LocalDate today) {}
    public record PartnerStatusWrite(Long id, String useYn, Long actor) {}
    public record PartnerHistoryWrite(Long id, String type, String before, String after, Long actor) {}
    public record PartnerAccessWrite(String filename, String detail, Long actor) {}
    public record PartnerPayment(@Sensitive String bank_cd, @Sensitive String acnt_no, @Sensitive String dpstr_nm) {}
    public record PartnerFile(String filename, byte[] bytes) {}
}
