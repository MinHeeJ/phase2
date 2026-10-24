package com.sicost.apiops;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import com.sicost.uploadsops.UploadInspectionContexts;
import com.sicost.uploadsops.UploadInspectionContexts.Inspection;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = UploadResultController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, UploadResultService.class,
        UploadInspectionContexts.class, PermissionService.class, UploadResultApiTest.Time.class})
class UploadResultApiTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-22T00:00:00Z");
        public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @TestConfiguration static class Time { @Bean MutableClock clock() { return new MutableClock(); } }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UploadInspectionContexts contexts;
    @Autowired MutableClock clock;
    @MockBean UploadResultMapper mapper;
    private static long nextId = 100;
    private long id;

    @BeforeEach void setup() {
        clock.now = Instant.parse("2026-10-22T00:00:00Z");
        id = ++nextId;
        when(mapper.existingSalaryCount("2026-10")).thenReturn(3L);
    }
    void publish(String form, int errors, int warnings, boolean applicable) {
        contexts.publish(new Inspection(id, form, "B.1".equals(form) ? "2026-10" : null,
                5, errors, warnings, applicable, true), clock.instant());
    }

    @Test void publicSalaryResultIsRepeatableAndReadOnly() throws Exception {
        publish("B.1", 0, 1, true);
        String previous = null;
        for (int i = 0; i < 2; i++) {
            var response = mvc.perform(get("/api/uploads/{uploadId}", id))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.upload_id").value(id))
                    .andExpect(jsonPath("$.data.form_type").value("B.1"))
                    .andExpect(jsonPath("$.data.month").value("2026-10"))
                    .andExpect(jsonPath("$.data.row_count").value(5))
                    .andExpect(jsonPath("$.data.error_count").value(0))
                    .andExpect(jsonPath("$.data.warning_count").value(1))
                    .andExpect(jsonPath("$.data.can_apply").value(true))
                    .andExpect(jsonPath("$.data.existing_salary_count").value(3))
                    .andExpect(jsonPath("$.data.overwrite_notice").value("2026-10 급여 3명분이 이미 있습니다. 반영하면 새 파일로 모두 바뀝니다."))
                    .andExpect(jsonPath("$.data.issues_url").value("/api/uploads/" + id + "/issues"))
                    .andExpect(jsonPath("$.data.normalized_rows_available").value(true))
                    .andReturn().getResponse();
            String current = json.readTree(response.getContentAsByteArray()).get("data").toString();
            if (previous != null) assertThat(current).isEqualTo(previous);
            previous = current;
            assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        }
        verify(mapper, times(2)).existingSalaryCount("2026-10");
        verifyNoMoreInteractions(mapper);
        assertThat(contexts.require(id).canApply()).isTrue();
    }

    @Test void errorsBlockApplicabilityWithoutLosingSummary() throws Exception {
        publish("B.1", 2, 1, true);
        mvc.perform(get("/api/uploads/{uploadId}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.error_count").value(2))
                .andExpect(jsonPath("$.data.warning_count").value(1))
                .andExpect(jsonPath("$.data.can_apply").value(false));
    }

    @Test void developerResultHasExplicitNullMonthAndNoSalaryNotice() throws Exception {
        publish("B.8", 0, 0, true);
        var response = mvc.perform(get("/api/uploads/{uploadId}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.form_type").value("B.8"))
                .andExpect(jsonPath("$.data.existing_salary_count").value(0)).andReturn().getResponse();
        var result = json.readTree(response.getContentAsByteArray()).get("data");
        assertThat(result.has("month")).isTrue();
        assertThat(result.get("month").isNull()).isTrue();
        assertThat(result.get("overwrite_notice").isNull()).isTrue();
        assertThat(result.has("items")).isFalse();
        assertThat(result.has("token")).isFalse();
        verifyNoInteractions(mapper);
    }

    @Test void salaryWithoutExistingPeopleHasNoOverwriteNotice() throws Exception {
        when(mapper.existingSalaryCount("2026-10")).thenReturn(0L);
        publish("B.1", 0, 0, true);
        mvc.perform(get("/api/uploads/{uploadId}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.existing_salary_count").value(0))
                .andExpect(jsonPath("$.data.overwrite_notice").isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808", "999999999999999999999"})
    void invalidPathHasExistingApiErrorContract(String path) throws Exception {
        var response = mvc.perform(get("/api/uploads/" + path)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.field").value("uploadId"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty()).andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsByteArray()).get("requestId").asText())
                .isEqualTo(response.getHeader("X-Request-Id"));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest @ValueSource(longs = {1800, 1801})
    void resultExpiresAtThirtyMinutesWithoutExtendingOnRead(long seconds) throws Exception {
        publish("B.8", 0, 0, true);
        clock.now = clock.now.plusSeconds(1799);
        mvc.perform(get("/api/uploads/{uploadId}", id)).andExpect(status().isOk());
        clock.now = Instant.parse("2026-10-22T00:00:00Z").plusSeconds(seconds);
        mvc.perform(get("/api/uploads/{uploadId}", id)).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VALIDATION_EXPIRED"))
                .andExpect(jsonPath("$.field").value("uploadId"))
                .andExpect(jsonPath("$.message").value("검사 결과가 만료되었습니다. 파일을 다시 올려 주세요."))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper);
    }

    @Test void absentContextAfterRestartRequiresReupload() throws Exception {
        mvc.perform(get("/api/uploads/{uploadId}", id)).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VALIDATION_EXPIRED"));
        verifyNoInteractions(mapper);
    }

    @Test void revalidatedOrAppliedContextRemainsReadableButNotApplicable() throws Exception {
        publish("B.8", 0, 0, true);
        contexts.invalidate(id);
        mvc.perform(get("/api/uploads/{uploadId}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.row_count").value(5))
                .andExpect(jsonPath("$.data.can_apply").value(false));
        long fresh = id + 10000;
        contexts.publish(new Inspection(fresh, "B.8", null, 5, 0, 0, true, true), clock.instant());
        mvc.perform(get("/api/uploads/{uploadId}", fresh)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_apply").value(true));
        assertThat(contexts.require(id).canApply()).isFalse();
    }

    @Test void serverFailureDoesNotLeakPersistenceDetails() throws Exception {
        publish("B.1", 0, 0, true);
        when(mapper.existingSalaryCount("2026-10")).thenThrow(new IllegalStateException("private SQL cst.mth_cst"));
        mvc.perform(get("/api/uploads/{uploadId}", id)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        assertThat(contexts.require(id).canApply()).isTrue();
    }
}
