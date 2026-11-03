package com.sicost.apiops;

import com.sicost.common.api.ApiException;
import java.time.*;

public final class DashboardInput {
    private DashboardInput() {}
    public record Context(Instant asOf, LocalDate today, YearMonth basisMonth) {
        public YearMonth currentMonth() { return YearMonth.from(today); }
    }
    public static Context capture(String month, Clock clock) {
        Instant instant=clock.instant();
        LocalDate today=instant.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        YearMonth current=YearMonth.from(today),selected=current;
        if(month!=null) {
            if(!month.matches("(?!0000)[0-9]{4}-(0[1-9]|1[0-2])"))
                throw new ApiException(400,"INVALID_MONTH","올바른 기준월을 입력하세요.","month");
            selected=YearMonth.parse(month);
        }
        if(selected.isAfter(current))throw new ApiException(400,"FUTURE_MONTH","미래 기준월은 조회할 수 없습니다.","month");
        return new Context(instant,today,selected);
    }
}
