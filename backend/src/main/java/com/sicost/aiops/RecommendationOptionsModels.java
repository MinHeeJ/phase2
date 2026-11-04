package com.sicost.aiops;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class RecommendationOptionsModels {
    private RecommendationOptionsModels() {}
    public record Query(String direction, Long sourceId, Long devId, String search) {}
    public record SourceReference(String sourceType, long rowId) implements java.io.Serializable {}
    public record SourceRow(String sourceType, long rowId, Long projectId, Long needId,
            String jobCode, String gradeCode, String startYm, int durationMonths,
            int remainingHeadcount, BigDecimal requestedMm, Long customerId, String industryCode) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Criteria(String jobCode, String startYm, String gradeCode, List<String> skillCodes,
            Long customerId, String industryCode, int durationMonths, int headcount, BigDecimal requestedMm) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceOption(long sourceId, String sourceType, Long projectId, Long needId,
            Criteria criteria, int remainingHeadcount) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ExitOption(long devId, LocalDate exitDate, String assumedExitYm,
            String extensionLikelihood, boolean hasNextConfirmedAssignment) {}
    public record Options(List<SourceOption> sources, List<ExitOption> developers,
            List<String> jobCodes, List<String> gradeCodes, List<String> skillCodes, String defaultStartYm) {}
    public record ExitQuery(LocalDate today, LocalDate until, Long devId, String search) {}
}
