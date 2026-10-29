package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.api.BillingPaging;
import com.sicost.common.security.*;
import com.sicost.common.validation.BillingNumbers;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BillingCommonContractTest {
    @Test void phaseComponentsCanBeScannedTogetherWithoutBeanNameCollisions() {
        var registry = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var scanner = new org.springframework.context.annotation.ClassPathBeanDefinitionScanner(registry);
        scanner.scan("com.sicost.billings", "com.sicost.billingsops", "com.sicost.receipts");
        assertThat(registry.getBeanDefinition("billingController").getBeanClassName())
            .isEqualTo("com.sicost.billings.BillingController");
        assertThat(registry.getBeanDefinition("billingOperationsController").getBeanClassName())
            .isEqualTo("com.sicost.billingsops.BillingController");
        assertThat(registry.getBeanDefinition("billingAmountService").getBeanClassName())
            .isEqualTo("com.sicost.billings.BillingAmountService");
        assertThat(registry.getBeanDefinition("billingPreviewAmountService").getBeanClassName())
            .isEqualTo("com.sicost.billingsops.BillingAmountService");
        assertThat(registry.getBeanDefinition("billingReceivableService").getBeanClassName())
            .isEqualTo("com.sicost.billingsops.BillingReceivableService");
        assertThat(registry.getBeanDefinition("billingReceiptBalanceService").getBeanClassName())
            .isEqualTo("com.sicost.receipts.BillingReceivableService");
    }

    void invalid(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.field()).isEqualTo(field);
        });
    }

    @ParameterizedTest @CsvSource({"0,B-2610-01", "98,B-2610-99", "99,B-2610-100", "999,B-2610-1000"})
    void numberUsesCapturedCreationMonthAndNeverTruncatesSuffix(long maximum, String expected) {
        assertThat(BillingNumbers.allocated(YearMonth.of(2026, 10), maximum)).isEqualTo(expected);
    }

    @Test void numbersAreSeparatedByMonth() {
        assertThat(BillingNumbers.allocated(YearMonth.of(2026, 11), 0)).isEqualTo("B-2611-01");
        assertThat(BillingNumbers.prefix(YearMonth.of(2027, 1))).isEqualTo("B-2701-");
    }

    @ParameterizedTest @ValueSource(longs = {-1, Long.MAX_VALUE, 9999999999999L})
    void numberRejectsSuffixOverflowAndPhysicalLength(long maximum) {
        invalid(() -> BillingNumbers.allocated(YearMonth.of(2026, 10), maximum), "bill_no");
    }

    @Test void numberRequiresCapturedCreationMonth() {
        invalid(() -> BillingNumbers.allocated(null, 0), "bill_no");
    }

    @Test void pagingReusesZeroBasedDeveloperContractAndExistingEnvelope() {
        var defaults = BillingPaging.of(null, null, null);
        assertThat(defaults.page()).isZero();
        assertThat(defaults.size()).isEqualTo(50);
        assertThat(defaults.offset()).isZero();
        var page = new BillingPaging(2, 20, "bill_dt,desc");
        assertThat(page.offset()).isEqualTo(40);
        var result = page.result(List.of("selected row"), 41);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(41);
        assertThat(result.items()).containsExactly("selected row");
    }

    @ParameterizedTest @ValueSource(ints = {-1, Integer.MAX_VALUE})
    void invalidPageIsBillingValidation(int page) {
        invalid(() -> new BillingPaging(page, 50, null), "page");
    }

    @ParameterizedTest @ValueSource(ints = {0, 10, 30, 101})
    void unsupportedSizeIsBillingValidation(int size) {
        invalid(() -> new BillingPaging(0, size, null), "size");
    }

    @ParameterizedTest @ValueSource(ints = {20, 50, 100})
    void allowedPageSizesAreUnchanged(int size) {
        assertThat(new BillingPaging(0, size, null).size()).isEqualTo(size);
    }

    @Test void whitelistReturnsDataNotInterpolatedSql() {
        assertThat(new BillingPaging(0, 50, null).allowedSort(Set.of("bill_dt"), "bill_dt,desc"))
            .isEqualTo(new BillingPaging.Sort("bill_dt", true));
        assertThat(new BillingPaging(0, 50, "bill_dt,asc").allowedSort(Set.of("bill_dt"), "bill_dt,desc"))
            .isEqualTo(new BillingPaging.Sort("bill_dt", false));
    }

    @ParameterizedTest @ValueSource(strings = {
        "bill_dt", "bill_dt,DESC", "bill_dt,desc,extra", "bill_dt;DROP TABLE bill.bill,asc",
        "not_a_response_field,asc", "bill_dt, desc", "bill_dt,"
    })
    void malformedOrUnlistedSortCannotReachSql(String sort) {
        invalid(() -> new BillingPaging(0, 50, sort).allowedSort(Set.of("bill_dt"), "bill_dt,desc"), "sort");
    }

    @ParameterizedTest @CsvSource({
        "listBillings,UI-13.read", "getBilling,UI-13.read", "previewBilling,UI-13.read",
        "listBillingReceipts,UI-13.read", "getDispatchAttendance,UI-13.read", "listBillingGaps,UI-13.read",
        "listBillingOverdue,UI-13.read", "getBillingSummary,UI-13.read", "createBilling,UI-13.create",
        "createBillingAmendment,UI-13.create", "updateBilling,UI-13.update", "saveDispatchAttendance,UI-13.update",
        "confirmBilling,UI-13.confirm", "cancelBilling,UI-13.cancel", "createBillingReceipt,UI-13.receipt",
        "updateBillingReceipt,UI-13.receipt", "deleteBillingReceipt,UI-13.receipt",
        "validateDispatchAttendanceUpload,UI-13.upload", "applyDispatchAttendanceUpload,UI-13.upload",
        "exportBillings,UI-13.download", "exportBillingGaps,UI-13.download", "exportBillingOverdue,UI-13.download",
        "getUploadResult,UI-13.upload", "listUploadRows,UI-13.upload", "listUploadIssues,UI-13.upload",
        "createUploadRevalidation,UI-13.upload", "saveUploadApplication,UI-13.upload",
        "getUploadTemplate,UI-13.download", "getUploadErrorFile,UI-13.download"
    })
    void billingActionKeysUseExistingAlwaysAllowPermissionService(String operation, String key) {
        assertThat(BillingActions.key(operation)).isEqualTo(key);
        assertThat(BillingActions.can(new PermissionService(), operation)).isTrue();
        assertThat(new CurrentUser().userId()).isNull();
    }

    @Test void reminderHasOnlyAnInertPermissionSlotNotASendingOperation() {
        assertThat(BillingActions.REMIND).isEqualTo("UI-13.remind");
        assertThatThrownBy(() -> BillingActions.key("sendReminder")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unknownOperationsDoNotSilentlyAcquireReadKey() {
        assertThatThrownBy(() -> BillingActions.key("listAssignments")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillingActions.key(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AssignmentActions.key("listAssignments")).isEqualTo("UI-12.read");
    }
}
