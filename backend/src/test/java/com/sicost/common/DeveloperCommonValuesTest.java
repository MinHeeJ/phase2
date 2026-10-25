package com.sicost.common;

import static org.assertj.core.api.Assertions.*;

import com.sicost.common.api.ApiException;
import com.sicost.common.api.DeveloperPaging;
import com.sicost.common.api.PageRequest;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.security.PermissionService;
import com.sicost.common.validation.DeveloperManagementNumbers;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DeveloperCommonValuesTest {
    @ParameterizedTest
    @CsvSource({"1,K-0001", "9999,K-9999", "10000,K-10000", "100000,K-100000"})
    void allocatedNumbersUseAtLeastFourDigitsWithoutTruncating(long id, String expected) {
        assertThat(DeveloperManagementNumbers.allocated(id)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void unallocatedIdentifiersAreRejected(long id) {
        assertThatThrownBy(() -> DeveloperManagementNumbers.allocated(id))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(400);
                assertThat(error.field()).isEqualTo("dev_id");
            });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankSuppliedNumbersRequestAllocation(String input) {
        assertThat(DeveloperManagementNumbers.supplied(input)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"K-0001", "ABC123", "A-123456789012345678", "-"})
    void validSuppliedNumbersArePreservedLiterally(String input) {
        assertThat(DeveloperManagementNumbers.supplied(input)).isEqualTo(input);
    }

    @Test void lowercaseIsRejectedRatherThanUppercased() { invalidNumber("k-0001"); }
    @Test void twentyOneCharactersAreRejected() { invalidNumber("A12345678901234567890"); }
    @Test void leadingWhitespaceIsNotSilentlyRemoved() { invalidNumber(" K-0001"); }
    @Test void underscoreIsNotAManagementNumberCharacter() { invalidNumber("K_0001"); }

    private void invalidNumber(String input) {
        assertThatThrownBy(() -> DeveloperManagementNumbers.supplied(input))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(400);
                assertThat(error.code()).isEqualTo("INVALID_INPUT");
                assertThat(error.field()).isEqualTo("mng_no");
            });
    }

    @Test void duplicateNumberHasTheDocumentedConflictShape() {
        var error = DeveloperManagementNumbers.duplicate();
        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo("DUPLICATE_MNG_NO");
        assertThat(error.field()).isEqualTo("mng_no");
    }

    @Test void firstDeveloperPageAdaptsOnlyTheInternalRequest() {
        var paging = new DeveloperPaging(0, 50, "dev_nm,asc");
        assertThat(paging.toPageRequest().page()).isEqualTo(1);
        assertThat(paging.toPageRequest().offset()).isZero();
        var result = paging.result(List.of(42L), 1);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(50);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).containsExactly(42L);
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 50, 100})
    void subsequentPagesUseTheExistingOffsetAndSizeRules(int size) {
        var paging = new DeveloperPaging(2, size, null);
        assertThat(paging.toPageRequest().page()).isEqualTo(3);
        assertThat(paging.toPageRequest().offset()).isEqualTo(2L * size);
    }

    @Test void negativePageIsAnInputError() { invalidPage(-1, 50, "page"); }
    @Test void pageConversionCannotOverflow() { invalidPage(Integer.MAX_VALUE, 50, "page"); }
    @Test void unsupportedPageSizeIsAnInputError() { invalidPage(0, 30, "size"); }

    private void invalidPage(int page, int size, String field) {
        assertThatThrownBy(() -> new DeveloperPaging(page, size, null))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(400);
                assertThat(error.field()).isEqualTo(field);
            });
    }

    @Test void sortStillRequiresTheCallersAllowlist() {
        var request = new DeveloperPaging(0, 50, "dev_nm,desc").toPageRequest();
        assertThat(request.allowedSort(Set.of("dev_nm,asc", "dev_nm,desc"), "dev_nm,asc"))
            .isEqualTo("dev_nm,desc");
        assertThatThrownBy(() -> new DeveloperPaging(0, 50, "dev_nm;DROP TABLE mst.dev")
            .toPageRequest().allowedSort(Set.of("dev_nm,asc"), "dev_nm,asc"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.field()).isEqualTo("sort"));
    }

    @Test void legacyPagingRemainsOneBased() {
        assertThat(new PageRequest(1, 50, null).offset()).isZero();
        assertThatThrownBy(() -> new PageRequest(0, 50, null)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "create", "update", "delete", "upload", "download"})
    void developerPermissionKeysRemainAlwaysAllowWithoutAuthentication(String action) {
        assertThat(PermissionService.AUTH_COLUMNS).containsKey(action);
        assertThat(new PermissionService().can("UI-02." + action)).isTrue();
        assertThat(new CurrentUser().userId()).isNull();
    }
}
