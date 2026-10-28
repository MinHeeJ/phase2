package com.sicost.common;

import static com.sicost.common.validation.BillingRules.*;
import static org.assertj.core.api.Assertions.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BillingRulesTest {
    final YearMonth october = YearMonth.of(2026, 10);
    final LocalDate billDate = LocalDate.of(2026, 11, 5);
    final Clock clock = Clock.fixed(Instant.parse("2025-12-31T15:00:00Z"), ZoneOffset.UTC);

    void error(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    @Test void internalProjectCannotBeBilled() {
        error(() -> requireBillable("IN"), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"OTHER", "tk", " TK"})
    void unknownProjectTypeIsNotNormalized(String type) {
        error(() -> requireBillable(type), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest @ValueSource(strings = {"TK", "MO", "DP"})
    void supportedProjectTypesAreBillable(String type) { requireBillable(type); }

    @Test void turnkeyRevenueUsesBillDateInsteadOfAttributionOrReceiptDate() {
        var period = new Period("TK", "2026-10", billDate);
        assertThat(accountingMonth(period)).isEqualTo(YearMonth.of(2026, 11));
        requireReceiptMoveOpen(null, LocalDate.of(2027, 1, 1), Set.of());
        assertThat(accountingMonth(period)).isEqualTo(YearMonth.of(2026, 11));
    }

    @ParameterizedTest @ValueSource(strings = {"MO", "DP"})
    void monthlyRevenueDoesNotMoveWithBillOrReceiptDate(String type) {
        var period = new Period(type, "2026-10", billDate);
        requireReceiptMoveOpen(null, LocalDate.of(2027, 1, 1), Set.of());
        assertThat(accountingMonth(period)).isEqualTo(october);
        assertThat(accountingMonth(new Period(type, "2026-10", null))).isEqualTo(october);
    }

    @Test void undatedTurnkeyDraftHasNoInventedAccountingMonth() {
        assertThat(accountingMonth(new Period("TK", null, null))).isNull();
        requireBillOpen(new Period("TK", null, null), Set.of(october));
    }

    @ParameterizedTest @EnumSource(Status.class)
    void allFourStatesRemainDistinctAndOnlyConfirmedSupplyIsRevenue(Status current) {
        String value = current.name();
        assertThat(status(value)).isEqualTo(current);
        assertThat(confirmedSupply(value, 100)).isEqualTo(current == Status.CONFIRM ? 100 : 0);
        assertThat(plannedSupply(value, 100)).isEqualTo(current == Status.PLAN ? 100 : 0);
        assertThat(draftSupply(value, 100)).isEqualTo(current == Status.DRAFT ? 100 : 0);
    }

    @Test void confirmedNegativeSupplyIsIncludedInRevenue() {
        assertThat(confirmedSupply("CONFIRM", -20)).isEqualTo(-20);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"confirm", "OTHER"})
    void unknownStatusDoesNotBecomeDraft(String value) {
        error(() -> status(value), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest @ValueSource(strings = {"MO", "DP"})
    void closedMonthlyBillUsesAttributionRatherThanBillMonth(String type) {
        var period = new Period(type, "2026-10", billDate);
        error(() -> requireBillOpen(period, Set.of(october)), 409, "CLOSED_MONTH");
        requireBillOpen(period, Set.of(YearMonth.from(billDate)));
    }

    @Test void closedTurnkeyBillUsesBillMonthRatherThanAttribution() {
        var period = new Period("TK", "2026-10", billDate);
        error(() -> requireBillOpen(period, Set.of(YearMonth.from(billDate))), 409, "CLOSED_MONTH");
        requireBillOpen(period, Set.of(october));
    }

    @ParameterizedTest @ValueSource(strings = {"TK", "MO", "DP"})
    void oldClosedMonthCannotBeEvadedByMovingToOpenMonth(String type) {
        var before = new Period(type, "2026-10", LocalDate.of(2026, 10, 1));
        var after = new Period(type, "2026-11", billDate);
        error(() -> requireBillMoveOpen(before, after, Set.of(october)), 409, "CLOSED_MONTH");
    }

    @ParameterizedTest @ValueSource(strings = {"TK", "MO", "DP"})
    void newClosedMonthRejectsMoveFromOpenMonth(String type) {
        var before = new Period(type, "2026-11", billDate);
        var after = new Period(type, "2026-10", LocalDate.of(2026, 10, 1));
        error(() -> requireBillMoveOpen(before, after, Set.of(october)), 409, "CLOSED_MONTH");
    }

    @Test void removingDateCannotEvadeOldTurnkeyClose() {
        error(() -> requireBillMoveOpen(new Period("TK", null, billDate),
            new Period("TK", null, null), Set.of(YearMonth.from(billDate))), 409, "CLOSED_MONTH");
    }

    @Test void closedAttendanceMonthIsRejected() {
        error(() -> requireOpen(october, Set.of(october), "month"), 409, "CLOSED_MONTH");
    }

    @Test void pastYearIsOnlyPresentationPolicyNotAnApiCloseRule() {
        assertThat(pastYear(2025, clock)).isTrue();
        assertThat(pastYear(2026, clock)).isFalse();
        assertThat(pastYear(2027, clock)).isFalse();
        requireBillOpen(new Period("MO", "2025-10", null), Set.of());
    }

    @Test void closedOldReceiptMonthRejectsEditOrDeletion() {
        var old = october.atDay(1);
        error(() -> requireReceiptMoveOpen(old, billDate, Set.of(october)), 409, "CLOSED_MONTH");
        error(() -> requireReceiptMoveOpen(old, null, Set.of(october)), 409, "CLOSED_MONTH");
    }

    @Test void closedNewReceiptMonthRejectsCreateOrEditIndependentlyOfBillMonth() {
        var requested = october.atDay(1);
        error(() -> requireReceiptMoveOpen(null, requested, Set.of(october)), 409, "CLOSED_MONTH");
        error(() -> requireReceiptMoveOpen(billDate, requested, Set.of(october)), 409, "CLOSED_MONTH");
    }

    @Test void missingReceiptDatesAreInvalid() {
        error(() -> requireReceiptMoveOpen(null, null, Set.of()), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest @ValueSource(strings = {"PLAN", "CONFIRM", "CANCEL"})
    void onlyDraftMayBeEditedOrConfirmedAndReconfirmIsNotIdempotent(String current) {
        error(() -> requireDraft(current), 409, "STALE_BILL");
    }

    @Test void lockedDraftMayBeEditedAndConfirmed() { requireDraft("DRAFT"); }

    @ParameterizedTest @ValueSource(strings = {"PLAN", "DRAFT", "CANCEL"})
    void receiptsRequireConfirmedOriginal(String current) {
        error(() -> requireOriginalConfirmed(current, false), 409, "STALE_BILL");
    }

    @Test void amendmentCannotReceiveMoneyEvenWhenConfirmed() {
        error(() -> requireOriginalConfirmed("CONFIRM", true), 409, "STALE_BILL");
        requireOriginalConfirmed("CONFIRM", false);
    }

    @ParameterizedTest @ValueSource(strings = {"DRAFT", "CONFIRM"})
    void eligibleCancellationRequiresNoReceiptRows(String current) {
        requireCancellable(current, 0);
        error(() -> requireCancellable(current, 1), 409, "STALE_BILL");
    }

    @ParameterizedTest @ValueSource(strings = {"PLAN", "CANCEL"})
    void otherStatesCannotBeCancelled(String current) {
        error(() -> requireCancellable(current, 0), 409, "STALE_BILL");
    }

    @ParameterizedTest @ValueSource(ints = {0, 2, -1})
    void conditionalUpdateMustAffectExactlyOneLockedBill(int rows) {
        error(() -> requireChanged(rows), 409, "STALE_BILL");
    }

    @Test void successfulConditionalUpdateIsAccepted() { requireChanged(1); }

    @ParameterizedTest @CsvSource({
        "listBillings,read", "previewBilling,read", "getBilling,read", "listBillingReceipts,read",
        "getDispatchAttendance,read", "listBillingGaps,read", "listBillingOverdue,read", "getBillingSummary,read",
        "createBilling,create", "createBillingAmendment,create", "updateBilling,update",
        "saveDispatchAttendance,update", "confirmBilling,confirm", "cancelBilling,cancel",
        "createBillingReceipt,receipt", "updateBillingReceipt,receipt", "deleteBillingReceipt,receipt",
        "validateDispatchAttendanceUpload,upload", "applyDispatchAttendanceUpload,upload",
        "getUploadResult,upload", "listUploadIssues,upload", "listUploadRows,upload",
        "createUploadRevalidation,upload", "getUploadTemplate,download", "getUploadErrorFile,download"
    })
    void permissionSlotsRemainPublicAndActorsRemainAnonymous(String operation, String action) {
        assertThat(BillingActions.key(operation)).isEqualTo("UI-13." + action);
        assertThat(BillingActions.can(new PermissionService(), operation)).isTrue();
        assertThat(new CurrentUser().userId()).isNull();
    }

    @Test void reminderSlotDoesNotIntroduceAnActualSendOperation() {
        assertThat(BillingActions.reminderKey()).isEqualTo("UI-13.remind");
        assertThat(new PermissionService().can(BillingActions.reminderKey())).isTrue();
        assertThatThrownBy(() -> BillingActions.key("sendBillingReminder"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
