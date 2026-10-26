package com.si.cost.common.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PageRequestTest {
    @Test void defaultPageIsZeroAndSizeIsFifty() {
        var page = PageRequest.of(null, null, null, "sort_seq");
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(50);
        assertThat(page.offset()).isZero();
    }
    @Test void allowedSizesAndDirections() {
        for (int size : new int[] {20, 50, 100}) {
            assertThat(PageRequest.of(2, size, "sort_seq,desc", "sort_seq").offset()).isEqualTo(2 * size);
        }
    }
    @Test void rejectsNegativePage() {
        assertThatThrownBy(() -> PageRequest.of(-1, 50, null, "sort_seq")).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsUnsupportedSize() {
        assertThatThrownBy(() -> PageRequest.of(0, 10, null, "sort_seq")).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsUntrustedSort() {
        assertThatThrownBy(() -> PageRequest.of(0, 50, "sort_seq;DROP TABLE,asc", "sort_seq")).isInstanceOf(BusinessException.class);
    }
    @Test void strictCalendarRejectsImpossibleDate() {
        assertThatThrownBy(() -> CalendarValues.date("2026-02-30", "date")).isInstanceOf(BusinessException.class);
        assertThat(CalendarValues.month("2026-10", "month").getMonthValue()).isEqualTo(10);
    }
}
