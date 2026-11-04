package com.sicost.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class AnalysisModels {
    private AnalysisModels() {}
    public enum Intent {
        PROJECT_PROFIT_RANKING, PROJECT_PROFIT_TREND, MONTHLY_FINANCE, RECEIVABLES_RISK,
        PROJECT_PROGRESS_RISK, WORKFORCE_CAPACITY, EXIT_AND_NEXT_POSITION, SALES_PIPELINE_FORECAST,
        CASH_FORECAST, CLOSE_STATUS, WHAT_IF_WORKFORCE, WHAT_IF_PROJECT_COST, FORECAST_PROFIT,
        FORECAST_STAFF, FORECAST_CASH, FORECAST_RECEIVABLES, FORECAST_TURNKEY
    }
    public record Filters(List<Long> projectIds, List<Long> customerIds, List<Long> partIds, List<Long> devIds,
            List<String> jobCodes, List<String> gradeCodes, List<String> skillCodes) {
        public Set<Long> references() {
            var ids = new HashSet<Long>(); ids.addAll(projectIds); ids.addAll(customerIds);
            ids.addAll(partIds); ids.addAll(devIds); return ids;
        }
    }
    public record WorkforceAssumption(String type, Long opportunityId, Long assignmentId, BigDecimal probability) {}
    public record VirtualCost(BigDecimal revenue, BigDecimal cost, BigDecimal costChangePercent,
            BigDecimal requestedMm, Integer durationMonths) {
        @Override public String toString() { return "VirtualCost[redacted]"; }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Query(Intent intent, String baseYm, String fromYm, String toYm, String periodType,
            String sort, int limit, Filters filters, List<WorkforceAssumption> workforceAssumptions,
            Boolean needsClarification) {}
    public record Request(Query query, VirtualCost virtual) {
        @Override public String toString() { return "AnalysisRequest[redacted]"; }
    }
    public record MessageInput(String question, String baseYm, Long contextMessageId) {
        @Override public String toString() { return "MessageInput[redacted]"; }
    }
    public record FeedbackInput(String rating, String reason) {}
    public record Period(String baseYm, String fromYm, String toYm, LocalDate basisDate) {}
    public record Source(String sourceService, List<String> sourceScreenIds, String baseYm,
            String closedStatus, OffsetDateTime generatedAt) {}
    public record Chart(String type, String xField, List<String> yFields) {}
    public record AreaError(Intent analysisType, String code, String message) {}
    public record Section(Intent analysisType, Query criteria, Period period, List<String> sourceScreenIds,
            String sourceService, String closedStatus, OffsetDateTime generatedAt, String unit,
            Map<String,BigDecimal> metrics, List<?> rows, Chart chartSpec, List<String> assumptions,
            List<String> warnings, List<Evaluation> evaluations) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Answer(Long messageId, String status, Query interpretedQuery, String answer,
            Map<String,BigDecimal> metrics, List<Section> table, List<Chart> chart, List<Source> sourceRefs,
            Period period, String closedStatus, List<String> assumptions, List<String> warnings,
            @JsonInclude(JsonInclude.Include.ALWAYS) String clarification,
            boolean modelUsed, boolean fallbackUsed, List<AreaError> areaErrors) {
        public Answer withMessage(long id) { return new Answer(id,status,interpretedQuery,answer,metrics,table,chart,
            sourceRefs,period,closedStatus,assumptions,warnings,clarification,modelUsed,fallbackUsed,areaErrors); }
        public Answer withModel(boolean used) { return new Answer(messageId,status,interpretedQuery,answer,metrics,table,chart,
            sourceRefs,period,closedStatus,assumptions,warnings,clarification,used,!used,areaErrors); }
    }
    public record Capability(Intent intent, boolean available, List<String> requiredFields, List<String> allowedFilters,
            int maxMonths, int maxLimit, String unavailableReason) {}
    public record Capabilities(List<Capability> intents, String modelStatus, List<String> suggestedQuestions, boolean modelUsed) {}
    public record ProfitRow(long projectId, String projectName, String month, BigDecimal revenue, BigDecimal cost,
            BigDecimal profit, BigDecimal marginPercent, String closedStatus) {}
    public record FinanceRow(String month, Long partId, BigDecimal revenue, BigDecimal operatingProfit,
            BigDecimal revenueTarget, BigDecimal operatingProfitTarget, String closedStatus) {}
    public record ReceivableRow(long projectId, Long customerId, LocalDate dueDate, BigDecimal balance,
            int overdueDays, Integer overdueThresholdDays, Integer paymentDelayDays, LocalDate basisDate) {}
    public record ProgressRow(long projectId, String trafficLight, int delayedIssueCount, Integer updateGapDays,
            LocalDate basisDate, boolean currentStateOnly) {}
    public record WorkforceRow(String month, String jobCode, String gradeCode, BigDecimal supplyMm, BigDecimal demandMm,
            BigDecimal confirmedMm, BigDecimal activeTemporaryMm, BigDecimal idleMm, BigDecimal utilizationPercent, String riskDirection) {}
    public record SalesRow(String month, BigDecimal confirmedRevenue, BigDecimal openOpportunityRevenueLow,
            BigDecimal openOpportunityRevenueBase, BigDecimal openOpportunityRevenueHigh,
            BigDecimal low, BigDecimal base, BigDecimal high) {}
    public record CashRow(String month, BigDecimal openingBalance, BigDecimal inflow, BigDecimal outflow,
            BigDecimal low, BigDecimal base, BigDecimal high, BigDecimal shortfall) {}
    public record Check(String code, String status, int count) {}
    public record CloseRow(String month, String closedStatus, List<Check> checks) {}
    public record CostRow(String month, BigDecimal revenue, BigDecimal baseCost, BigDecimal virtualCost,
            BigDecimal virtualProfit, BigDecimal virtualMarginPercent, BigDecimal costChangePercent) {}
    public record ProfitForecastRow(String month, BigDecimal low, BigDecimal base, BigDecimal high, String rangeUnavailableReason) {}
    public record TurnkeyRow(long projectId, BigDecimal finalRevenue, BigDecimal accumulatedCost, BigDecimal remainingCost,
            BigDecimal marginLowPercent, BigDecimal marginBasePercent, BigDecimal marginHighPercent, String rangeUnavailableReason) {}
    public record Evaluation(String baseYm, String targetYm, String forecastType, BigDecimal forecastValue,
            BigDecimal actualValue, BigDecimal absoluteDifference, BigDecimal errorPercent, String evaluationStatus) {}
    public record Conversation(long id, String questionSummary, String answerSummary, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, boolean visible) {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record Messages(List<StoredMessage> items, long total, int page, int size, Conversation conversation) {}
    public record Visibility(long id, boolean visible) {}
    public record Feedback(long messageId, String rating, String reason) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StoredMessage(long messageId, String questionSummary, String answerSummary, Intent intent,
            Query criteria, String baseYm, List<Source> sourceRefs, String status, OffsetDateTime createdAt, Feedback feedback) {}
    public record ConversationRow(long id, String ownerHash, LocalDateTime createdAt, LocalDateTime updatedAt,
            LocalDateTime hiddenAt, LocalDateTime retentionUntil, String questionSummary, String answerSummary) {}
    public record MessageRow(long id, long conversationId, String question, String answer, String intent,
            String criteria, String baseYm, String sources, String status, LocalDateTime createdAt, String rating, String reason) {}
    public record ConversationWrite(String ownerHash, LocalDateTime now, LocalDateTime expiresAt, LocalDateTime retentionUntil) {}
    public record MessageWrite(long conversationId, Long contextId, String question, String answer,
            Query criteria, String criteriaJson, String sourcesJson, String status, LocalDateTime now) {}
    public record ConversationQuery(String ownerHash, String search, LocalDateTime now, long offset, int size) {}
    public record MessageQuery(long id, long offset, int size) {}
    public record FeedbackWrite(long id, String rating, String reason) {}
    public record ForecastWrite(String month, String type, int months, String assumptions, LocalDateTime now) {}
    public record ForecastValue(long executionId, String month, String key, BigDecimal low, BigDecimal base, BigDecimal high) {}
    public record SavedForecast(String baseYm, String targetYm, String type, BigDecimal forecastValue) {}
}
