package com.sicost.common;

import com.sicost.common.ai.AiQuestionSanitizer;
import com.sicost.common.api.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AiQuestionSanitizerTest {
    final AiQuestionSanitizer sanitizer = new AiQuestionSanitizer();
    @Test void koreanIntentAndMonthSurviveResolvedIdentityRemoval() {
        var safe = sanitizer.prepare("2027-01 홍길동 개발자의 다음 자리를 추천해 줘", Map.of("홍길동", 41L));
        assertThat(safe.needsClarification()).isFalse();
        assertThat(safe.modelQuestion()).contains("2027-01", "다음 자리").doesNotContain("홍길동", "41");
        assertThat(safe.serverTargets().values()).containsExactly(41L);
        assertThat(safe.toString()).doesNotContain("홍길동", "41");
    }
    @Test void longestResolvedIdentityWinsWithoutLeakingPartialNames() {
        var safe = sanitizer.prepare("홍길동과 홍길의 2027-01 투입 현황", Map.of("홍길", 1L, "홍길동", 2L));
        assertThat(safe.modelQuestion()).doesNotContain("홍길", "홍길동");
        assertThat(safe.serverTargets()).hasSize(2);
    }
    @Test void contactsAreRemovedRatherThanUsedAsModelConditions() {
        var safe = sanitizer.prepare("2027-01 인력 수급 확인, 연락처 010-1234-5678 또는 sample@example.invalid", Map.of());
        assertThat(safe.modelQuestion()).contains("2027-01", "인력 수급").doesNotContain("010-1234-5678", "sample@");
    }
    @ParameterizedTest @ValueSource(strings={
        "홍길동님의 급여를 알려 줘", "홍길동의 원가를 기준으로 계산", "급여 80000000에 10% 증가", "매출 1억 원가 8천만 +10%",
        "SELECT * FROM cst.mth_cst", "jdbc:postgresql://private/db", "https://outside.invalid", "password=private", "<script>fetch('/api')</script>"})
    void unsafeOrUnresolvedMeaningRequiresClarificationAndHasNoModelPayload(String question) {
        var safe = sanitizer.prepare(question, Map.of());
        assertThat(safe.needsClarification()).isTrue();
        assertThat(safe.modelQuestion()).isEmpty();
        assertThat(safe.serverTargets()).isEmpty();
        assertThat(safe.toString()).doesNotContain(question);
    }
    @Test void privateMoneyDoesNotBecomeSafeJustBecausePersonWasResolved() {
        var safe = sanitizer.prepare("홍길동 급여 80000000과 매출 비교", Map.of("홍길동", 41L));
        assertThat(safe.needsClarification()).isTrue();
        assertThat(safe.modelQuestion()).isEmpty();
    }
    @Test void ordinaryAggregateQuestionRetainsItsMeaning() {
        var safe = sanitizer.prepare("지난달 프로젝트 진행 위험과 인력 수급을 보여 줘", Map.of());
        assertThat(safe.needsClarification()).isFalse();
        assertThat(safe.modelQuestion()).contains("지난달", "진행 위험", "인력 수급");
    }
    @Test void validationDoesNotEchoInputAndEnforcesContractLength() {
        assertThatThrownBy(() -> sanitizer.prepare(" ", Map.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sanitizer.prepare("가".repeat(1001), Map.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sanitizer.prepare(null, Map.of())).isInstanceOf(ApiException.class);
    }
    @Test void redactedBindingsCannotAccidentallySerializeIntoModelRequest() throws Exception {
        var safe = sanitizer.prepare("홍길동의 2027-01 다음 자리", Map.of("홍길동", 41L));
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(safe))
            .doesNotContain("홍길동", "41", "serverTargets");
    }
}
