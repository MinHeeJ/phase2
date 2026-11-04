package com.sicost.staffrecommendations;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class StaffRecommendationModels {
    private StaffRecommendationModels() {}
    public static final String A = "PROJECT_TO_PERSON", B = "PERSON_TO_POSITION";
    public record Create(String direction, Long sourceId, Long devId, ObjectNode criteria) {}
    public record Decision(String direction, String decision, String reason) {}
    public record DecisionResult(long resultId, String direction, String decisionStatus, String reason, boolean assignmentCreated) {}
    public record Snapshot(long requestId, String direction, ObjectNode criteria, String evaluatedAt,
            List<ObjectNode> results, List<String> missingDataFields, List<String> warnings) {}
    public record Draft(String status, ObjectNode criteria, String clarification, List<String> warnings, boolean modelUsed) {}
    public record History(List<Snapshot> items, long total, int page, int size, Quality quality) {}
    public record Rate(int numerator, int denominator, BigDecimal rate) {}
    public record Quality(Rate adoption, Rate assignmentConversion, Rate observedExtension,
            int validSampleCountLastThreeMonths, List<String> warnings) {}
    public record HistoryQuery(String owner, int page, int size, String direction, String search,
            String decisionStatus, String fromYm, String toYm) {}
    public record Stored(long id, String owner, String document, LocalDateTime createdAt) {}
    public record Person(long id, String document) {}
    public record ResultState(long id, long requestId, long devId, String decision, String reason, Long assignmentId, Long temporaryAssignmentId) {}
    public record RequestWrite(long id, String owner, String type, Long projectId, Long needId, String document, String weights, LocalDateTime at) {}
    public record ResultWrite(long id, long requestId, long devId, Long projectId, Long planId, Long needId,
            int rank, Breakdown scores, String reason, String warnings) {}
    public record Transition(long id, String direction, String decision, String reason) {}
    public record Breakdown(BigDecimal job_scr, BigDecimal skl_scr, BigDecimal cust_scr, BigDecimal grd_scr,
            BigDecimal achv_scr, BigDecimal exp_scr, BigDecimal asgn_psbl_scr) {
        @com.fasterxml.jackson.annotation.JsonIgnore
        public BigDecimal getTotal() { return total(); }
        public BigDecimal total() { return job_scr.add(skl_scr).add(cust_scr).add(grd_scr).add(achv_scr).add(exp_scr).add(asgn_psbl_scr); }
    }
}
