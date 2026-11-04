package com.sicost.common.dashboard;

import com.sicost.common.api.ApiException;
import java.time.*;

/** One captured time basis shared by dashboard reads and downloads. */
public record DashboardReadContext(OffsetDateTime asOf, LocalDate today, YearMonth month) {
    public static DashboardReadContext capture(String month, Clock clock) {
        var asOf=clock.instant().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        var current=YearMonth.from(asOf);
        YearMonth selected=current;
        if (month!=null) {
            if (!month.matches("[0-9]{4}-(0[1-9]|1[0-2])") || month.startsWith("0000-"))
                throw new ApiException(400,"INVALID_MONTH","기준월은 YYYY-MM 형식이어야 합니다.","month");
            selected=YearMonth.parse(month);
            if (selected.isAfter(current))
                throw new ApiException(400,"FUTURE_MONTH","미래 기준월은 조회할 수 없습니다.","month");
        }
        return new DashboardReadContext(asOf,asOf.toLocalDate(),selected);
    }
}
