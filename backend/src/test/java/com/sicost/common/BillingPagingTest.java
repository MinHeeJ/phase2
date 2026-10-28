package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.api.BillingPaging;
import com.sicost.common.api.BillingPaging.View;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BillingPagingTest {
    void invalid(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(e.field()).isEqualTo(field);
        });
    }
    @ParameterizedTest @EnumSource(View.class)
    void defaultPagingIsZeroBasedWithFiftyRows(View view) {
        var paging = BillingPaging.defaults(view);
        assertThat(paging.page()).isZero();
        assertThat(paging.size()).isEqualTo(50);
        assertThat(paging.offset()).isZero();
    }
    @Test void defaultsHaveStableIdentityTieBreakersAndNullsLast() {
        assertThat(BillingPaging.defaults(View.BILLINGS).order())
            .isEqualTo("\"bill_dt\" desc NULLS LAST, \"bill_id\" desc");
        assertThat(BillingPaging.defaults(View.VIRTUAL_PLANS).order())
            .isEqualTo("\"bill_plan_dt\" asc NULLS LAST, \"bill_stg_id\" asc");
        assertThat(BillingPaging.defaults(View.GAPS).order())
            .isEqualTo("\"project_name\" asc NULLS LAST, \"project_id\" asc");
        assertThat(BillingPaging.defaults(View.OVERDUE).order())
            .isEqualTo("\"overdue_days\" desc NULLS LAST, \"bill_id\" asc");
    }
    @ParameterizedTest @ValueSource(ints = {20, 50, 100})
    void allowedPageSizesUseLongOffsets(int size) {
        assertThat(new BillingPaging(Integer.MAX_VALUE, size, null, View.BILLINGS).offset())
            .isEqualTo((long) Integer.MAX_VALUE * size);
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, 1, 10, 200})
    void otherSizesAreRejected(int size) {
        invalid(() -> new BillingPaging(0, size, null, View.BILLINGS), "size");
    }
    @Test void negativePageIsRejected() {
        invalid(() -> new BillingPaging(-1, 50, null, View.BILLINGS), "page");
    }
    @ParameterizedTest @ValueSource(strings = {"", " ", "bill_dt", "bill_dt,DESC", "bill_dt,desc,asc",
        "bill_dt,desc;drop table bill.bill", "unknown,asc", "bill_dt, desc", " bill_dt,asc"})
    void malformedOrUnknownSortNeverBecomesSql(String sort) {
        invalid(() -> new BillingPaging(0, 50, sort, View.BILLINGS), "sort");
    }
    @Test void fieldsMustBelongToTheSelectedList() {
        invalid(() -> new BillingPaging(0, 50, "overdue_days,desc", View.BILLINGS), "sort");
        invalid(() -> new BillingPaging(0, 50, "bill_no,asc", View.GAPS), "sort");
    }
    @ParameterizedTest @CsvSource({"BILLINGS,bill_id", "VIRTUAL_PLANS,bill_stg_id", "GAPS,project_id", "OVERDUE,bill_id"})
    void identitySortDoesNotDuplicateTheSameColumn(View view, String identity) {
        assertThat(new BillingPaging(0, 50, identity + ",asc", view).order())
            .isEqualTo("\"" + identity + "\" asc NULLS LAST");
    }
    @Test void approvedSupplyAndCashFieldsRemainDistinctSorts() {
        assertThat(new BillingPaging(0, 20, "sply_amt,asc", View.BILLINGS).order()).startsWith("\"sply_amt\"");
        assertThat(new BillingPaging(0, 20, "received_amt,desc", View.OVERDUE).order()).startsWith("\"received_amt\"");
    }
}
