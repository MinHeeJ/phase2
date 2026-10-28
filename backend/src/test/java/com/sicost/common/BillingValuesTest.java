package com.sicost.common;

import static com.sicost.common.validation.BillingValues.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sicost.common.api.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;

class BillingValuesTest {
    final Set<String> sorts = Set.of("bill_dt,asc", "bill_dt,desc");
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    void invalid(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(e.field()).isEqualTo(field);
        });
    }

    @Test void wonParsingIsExactAndSignedWhenRequested() {
        assertThat(money(new BigDecimal("9223372036854775807"), 0, "sply_amt"))
            .isEqualTo(Long.MAX_VALUE);
        assertThat(money(new BigDecimal("-123"), Long.MIN_VALUE, "sply_amt")).isEqualTo(-123);
    }

    @ParameterizedTest @ValueSource(strings = {"1.1", "9223372036854775808", "-1"})
    void invalidWonAmountIsBillingFieldError(String value) {
        invalid(() -> money(new BigDecimal(value), 0, "sply_amt"), "sply_amt");
    }

    @Test void missingAmountIsBillingFieldError() { invalid(() -> money(null, 0, "sply_amt"), "sply_amt"); }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"2026-1", "2026-13", "0000-01", " 2026-10", "2026-10 "})
    void monthFormatIsPreservedRatherThanRepaired(String value) {
        invalid(() -> month(value, "month"), "month");
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"2026-02-29", "2026-1-01", "0000-01-01", "2026-10-28T00:00:00"})
    void invalidIsoDateIsBillingFieldError(String value) {
        invalid(() -> date(value, "bill_dt"), "bill_dt");
    }

    @Test void validIsoMonthAndLeapDateParse() {
        assertThat(month("2026-10", "month")).isEqualTo(YearMonth.of(2026, 10));
        assertThat(date("2024-02-29", "bill_dt")).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test void pagingReusesZeroBasedDeveloperContractAndDefaults() {
        var page = paging(null, null, null, sorts, "bill_dt,desc");
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(50);
        assertThat(page.sort()).isEqualTo("bill_dt,desc");
        assertThat(page.toPageRequest().offset()).isZero();
        assertThat(page.result(List.of(), 0).page()).isZero();
    }

    @ParameterizedTest @ValueSource(ints = {20, 50, 100})
    void pagingSupportsOnlyExistingSizes(int size) {
        var page = paging(2, size, "bill_dt,asc", sorts, "bill_dt,desc");
        assertThat(page.toPageRequest().offset()).isEqualTo(2L * size);
        assertThat(page.sort()).isEqualTo("bill_dt,asc");
    }

    @ParameterizedTest @ValueSource(ints = {-1, Integer.MAX_VALUE})
    void invalidPageIsBillingFieldError(int page) {
        invalid(() -> paging(page, 50, null, sorts, "bill_dt,desc"), "page");
    }

    @ParameterizedTest @ValueSource(ints = {0, 10, 101})
    void unsupportedPageSizeIsBillingFieldError(int size) {
        invalid(() -> paging(0, size, null, sorts, "bill_dt,desc"), "size");
    }

    @ParameterizedTest @ValueSource(strings = {"bill_dt", "bill_dt,DESC", "bill_dt,desc;drop table bill.bill"})
    void sortUsesExactAllowlist(String sort) {
        invalid(() -> paging(0, 50, sort, sorts, "bill_dt,desc"), "sort");
    }

    @Test void invalidFallbackCannotBypassSortAllowlist() {
        invalid(() -> paging(0, 50, null, sorts, "arbitrary"), "sort");
    }

    record Wire(long sply_amt, LocalDate bill_dt, YearMonth month) {}

    @Test void existingEnvelopeSerializesWonAsNumberAndDateMonthAsIso() throws Exception {
        var tree = json.readTree(json.writeValueAsBytes(ApiResponse.ok(
            new Wire(9007199254740993L, LocalDate.of(2026, 10, 28), YearMonth.of(2026, 10)))));
        assertThat(tree.path("success").asBoolean()).isTrue();
        assertThat(tree.path("data").path("sply_amt").isIntegralNumber()).isTrue();
        assertThat(tree.path("data").path("sply_amt").longValue()).isEqualTo(9007199254740993L);
        assertThat(tree.path("data").path("bill_dt").textValue()).isEqualTo("2026-10-28");
        assertThat(tree.path("data").path("month").textValue()).isEqualTo("2026-10");
    }

    MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "test-request-id");
        return request;
    }

    @ParameterizedTest @ValueSource(strings = {"/api/billings", "/api/billings/12", "/api/billings/12/confirm",
        "/api/billings/12/receipts/7", "/api/dispatch-attendance",
        "/api/uploads/dispatch-attendance/validate", "/api/uploads/dispatch-attendance/apply"})
    void sharedAdviceTranslatesOnlyBillingInputErrors(String path) throws Exception {
        var advice = new ApiExceptionHandler();
        var response = advice.application(ApiException.badRequest("입력값을 확인하세요.", "month"), request(path));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        var tree = json.readTree(json.writeValueAsBytes(response.getBody()));
        assertThat(tree.path("code").textValue()).isEqualTo("VALIDATION_ERROR");
        assertThat(tree.path("field").textValue()).isEqualTo("month");
        assertThat(tree.has("fields")).isFalse();
        assertThat(tree.path("requestId").textValue()).isEqualTo("test-request-id");
        var missing = advice.missing(new MissingServletRequestParameterException("year", "int"), request(path));
        assertThat(missing.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(missing.getBody().field()).isEqualTo("year");
    }

    @ParameterizedTest @ValueSource(strings = {"/api/projects", "/api/assignments", "/api/developers",
        "/api/billings-other", "/api/uploads/42", "/api/dispatch-attendance-other"})
    void legacyInputErrorsRemainUnchanged(String path) {
        var response = new ApiExceptionHandler().application(ApiException.badRequest("입력 오류", "id"), request(path));
        assertThat(response.getBody().code()).isEqualTo("INVALID_INPUT");
    }

    @ParameterizedTest @ValueSource(strings = {"CLOSED_MONTH", "STALE_BILL", "OVER_RECEIPT", "REFUND_REQUIRED"})
    void billingConflictsRetainTheirSpecificCodes(String code) {
        var response = new ApiExceptionHandler().application(ApiException.conflict(code, "업무 충돌", null),
            request("/api/billings/12"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo(code);
    }

    @Test void fileErrorsAreNotReplacedByGenericValidation() {
        var response = new ApiExceptionHandler().application(
            new ApiException(400, "UNSUPPORTED_FILE_TYPE", "파일 형식을 확인하세요.", "file"),
            request("/api/uploads/dispatch-attendance/validate"));
        assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_FILE_TYPE");
    }

    @Test void billingAdviceWorksUnderServletContextPath() {
        var request = request("/sicost/api/billings");
        request.setContextPath("/sicost");
        var response = new ApiExceptionHandler().application(ApiException.badRequest("입력 오류", "year"), request);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }
}
