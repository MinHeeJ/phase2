package com.sicost.common.ai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sicost.common.api.ApiException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AiQuestionSanitizer {
    private static final Pattern EXECUTABLE = Pattern.compile(
        "(?i)(\\b(select|insert|update|delete|drop|alter|truncate|execute|jdbc|password|secret|token)\\b|https?://|<[^>]+>|--|/\\*|[;`])");
    private static final Pattern CONTACT = Pattern.compile(
        "(?i)([a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}|(?<![0-9])(?:\\+82[- ]?)?0[0-9]{1,2}[- ]?[0-9]{3,4}[- ]?[0-9]{4}(?![0-9])|(?<![0-9])[0-9]{6}[- ]?[1-4][0-9]{6}(?![0-9]))");
    private static final Pattern PRIVATE_CONDITION = Pattern.compile(
        "(?i)(급여|연봉|주민|실제\\s*개인|개인\\s*원가|salary|payroll|[0-9][0-9,.]*\\s*(?:억|천만|백만|만원|원|%|퍼센트)|[가-힣]{2,4}(?:님|씨)|[가-힣]{2,4}의\\s*원가)");

    public record Prepared(String modelQuestion, boolean needsClarification, @JsonIgnore Map<String, Long> serverTargets) {
        public Prepared { serverTargets = Map.copyOf(serverTargets); }
        @Override public String toString() { return "Prepared[redacted,needsClarification=" + needsClarification + "]"; }
    }
    /** Resolved tokens must come from server source lookup, not client or model identity claims. */
    public Prepared prepare(String question, Map<String, Long> resolvedTokens) {
        if (question == null || question.isBlank() || question.length() > 1000)
            throw ApiException.badRequest("질문은 1자부터 1000자까지 입력하세요.", "question");
        String safe = Normalizer.normalize(question, Normalizer.Form.NFKC).strip();
        if (EXECUTABLE.matcher(safe).find()) return clarify();
        Map<String, Long> targets = new LinkedHashMap<>();
        if (resolvedTokens != null) {
            for (String token : resolvedTokens.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
                Long id = resolvedTokens.get(token);
                if (token.isBlank() || id == null || id <= 0) throw new IllegalArgumentException("서버 식별자 매핑을 확인하세요.");
                String normalized = Normalizer.normalize(token, Normalizer.Form.NFKC);
                if (safe.contains(normalized)) {
                    String marker = "[대상" + (targets.size() + 1) + "]";
                    safe = safe.replace(normalized, marker);
                    targets.put(marker, id);
                }
            }
        }
        safe = CONTACT.matcher(safe).replaceAll("[연락처 제거]");
        if (PRIVATE_CONDITION.matcher(safe).find()) return clarify();
        // Long unlabeled numeric inputs cannot be safely distinguished from private money or identifiers.
        String withoutDates = safe.replaceAll("[0-9]{4}-(0[1-9]|1[0-2])(?:-[0-9]{2})?", "[기간]");
        if (Pattern.compile("[0-9]{5,}").matcher(withoutDates).find()) return clarify();
        return new Prepared(safe, false, targets);
    }
    private Prepared clarify() { return new Prepared("", true, Map.of()); }
}
