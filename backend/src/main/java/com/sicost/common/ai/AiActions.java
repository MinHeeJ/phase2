package com.sicost.common.ai;

import java.util.Map;

public final class AiActions {
    public static final String VIEW_RECOMMENDATIONS = "UI-30.view";
    public static final String RECOMMEND = "UI-30.recommend";
    public static final String EXPORT_RECOMMENDATIONS = "UI-30.export";
    public static final String DECISION = "UI-30.decision";
    public static final String VIEW_ANALYSIS = "UI-26.view";
    public static final String ASK = "UI-26.ask";
    public static final String EXPORT_ANALYSIS = "UI-26.export";
    private static final Map<String, String> OPERATIONS = Map.ofEntries(
        Map.entry("createStaffRecommendation", RECOMMEND),
        Map.entry("getStaffRecommendationOptions", VIEW_RECOMMENDATIONS),
        Map.entry("listStaffRecommendationHistory", VIEW_RECOMMENDATIONS),
        Map.entry("getStaffRecommendation", VIEW_RECOMMENDATIONS),
        Map.entry("updateStaffRecommendationDecision", DECISION),
        Map.entry("exportDeveloperCareerCard", EXPORT_RECOMMENDATIONS),
        Map.entry("previewStaffRecommendationCriteria", RECOMMEND),
        Map.entry("createAnalysisConversation", VIEW_ANALYSIS),
        Map.entry("listAnalysisConversations", VIEW_ANALYSIS),
        Map.entry("getAnalysisConversation", VIEW_ANALYSIS),
        Map.entry("updateAnalysisConversationVisibility", VIEW_ANALYSIS),
        Map.entry("createAnalysisMessage", ASK),
        Map.entry("saveAnalysisMessageFeedback", ASK),
        Map.entry("getAnalysisCapabilities", VIEW_ANALYSIS),
        Map.entry("createStructuredAnalysis", ASK),
        Map.entry("exportAnalysisMessage", EXPORT_ANALYSIS),
        Map.entry("exportStructuredAnalysis", EXPORT_ANALYSIS));

    private AiActions() {}
    public static String key(String operationId) {
        String key = operationId == null ? null : OPERATIONS.get(operationId);
        if (key == null) throw new IllegalArgumentException("허용된 AI 동작이 아닙니다.");
        return key;
    }
    public static boolean contains(String key) { return OPERATIONS.containsValue(key); }
}
