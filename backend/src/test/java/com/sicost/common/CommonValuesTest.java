package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sicost.common.api.PageRequest;
import com.sicost.common.config.ClockConfig;
import com.sicost.common.security.*;
import com.sicost.common.validation.CalendarValues;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommonValuesTest {
    record Visible(@Sensitive String salary) {}
    @Test void fixedActorUsesNullAndAll() {
        var user = new CurrentUser();
        assertThat(user.userId()).isNull();
        assertThat(user.name()).isEqualTo("기본 사용자");
        assertThat(user.roleIds()).containsExactly(1L);
        assertThat(user.dataScope()).isEqualTo(DataScope.ALL);
        assertThat(DataScope.values()).containsExactly(DataScope.ALL, DataScope.PART, DataScope.PROJECT, DataScope.SELF);
    }
    @Test void everyPermissionIsAllowedWithoutRoleReads() {
        var permissions = new PermissionService();
        for (String action : PermissionService.AUTH_COLUMNS.keySet()) assertThat(permissions.can("UI-27." + action)).isTrue();
    }
    @Test void sensitiveAnnotationDoesNotMask() throws Exception {
        assertThat(JsonMapper.builder().build().writeValueAsString(new Visible("200000"))).contains("200000");
    }
    @Test void pagesAndSortAreValidated() {
        assertThat(new PageRequest(2, 20, null).offset()).isEqualTo(20);
        assertThatThrownBy(() -> new PageRequest(0, 20, null)).hasMessageContaining("페이지");
        assertThatThrownBy(() -> new PageRequest(1, 30, null)).hasMessageContaining("건수");
        assertThatThrownBy(() -> new PageRequest(1, 50, "bad").allowedSort(Set.of("name"), "name")).hasMessageContaining("정렬");
        assertThat(new PageRequest(1, 100, null).allowedSort(Set.of("name"), "name")).isEqualTo("name");
    }
    @Test void calendarStringsNeverNormalizeInvalidDates() {
        assertThat(CalendarValues.month("2026-10", "month")).isEqualTo(YearMonth.of(2026, 10));
        assertThatThrownBy(() -> CalendarValues.month("0000-01", "month")).hasMessageContaining("연월");
        assertThatThrownBy(() -> CalendarValues.month("2026-13", "month")).hasMessageContaining("연월");
        assertThatThrownBy(() -> CalendarValues.date("2026-02-29", "date")).hasMessageContaining("날짜");
    }
    @Test void testClockIsControlledButProductionIsNotFixed() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        assertThat(YearMonth.now(clock)).isEqualTo(YearMonth.of(2026, 10));
        assertThat(new ClockConfig().clock().getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }
}
