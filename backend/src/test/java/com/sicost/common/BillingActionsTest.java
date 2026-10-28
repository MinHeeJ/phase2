package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import com.sicost.common.security.BillingActions;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.security.PermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BillingActionsTest {
    @ParameterizedTest @CsvSource({
        "listBillings,read", "previewBilling,read", "getBilling,read", "listBillingReceipts,read",
        "getDispatchAttendance,read", "listBillingGaps,read", "listBillingOverdue,read", "getBillingSummary,read",
        "createBilling,create", "createBillingAmendment,create", "updateBilling,update",
        "saveDispatchAttendance,update", "confirmBilling,confirm", "cancelBilling,cancel",
        "createBillingReceipt,receipt", "updateBillingReceipt,receipt", "deleteBillingReceipt,receipt",
        "validateDispatchAttendanceUpload,upload", "applyDispatchAttendanceUpload,upload",
        "getBillingsExport,download", "getBillingGapsExport,download", "getBillingOverdueExport,download"
    })
    void everyBillingOperationUsesPublicPermissionSlot(String operation, String action) {
        assertThat(BillingActions.key(operation)).isEqualTo("UI-13." + action);
        assertThat(BillingActions.can(new PermissionService(), operation)).isTrue();
        assertThat(new CurrentUser().userId()).isNull();
    }
    @ParameterizedTest @ValueSource(strings = {"read", "create", "update", "confirm", "cancel", "upload", "download", "receipt", "remind"})
    void allRequestedSlotsExistWithoutEnforcingPermissions(String action) {
        assertThat(new PermissionService().can(BillingActions.slot(action))).isTrue();
        assertThat(BillingActions.slot(action)).isEqualTo("UI-13." + action);
    }
    @Test void remindIsOnlyAPlaceholderNotANotificationOperation() {
        assertThatThrownBy(() -> BillingActions.key("sendBillingReminder")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unrelatedOperationsAreNotRemapped() {
        assertThatThrownBy(() -> BillingActions.key("createAssignment")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillingActions.slot("delete")).isInstanceOf(IllegalArgumentException.class);
    }
}
