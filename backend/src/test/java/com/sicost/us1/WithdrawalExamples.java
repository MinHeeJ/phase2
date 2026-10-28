package com.sicost.us1;

import java.time.*;
import java.util.List;
import static com.sicost.us1.WithdrawalModels.*;

final class WithdrawalExamples {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    static final WithdrawalRules.DispatchStandard STANDARD = new WithdrawalRules.DispatchStandard(24, List.of(60, 30));
    static Source source(long id, long dev, String end, String chance, Long next) {
        return new Source(id, "A-" + id, dev, "개발자 " + dev, "K-" + dev, 1, "프로젝트", "BE", "백엔드 개발",
            LocalDate.of(2026, 1, 1), LocalDate.parse(end), LocalDate.of(2026, 10, 31), chance,
            chance == null ? "미정" : chance.equals("H") ? "높음" : "보통", "ACTIVE",
            LocalDateTime.parse("2026-10-14T00:00:00.123456"), 1, true, null, "MO", "RUN", "Y",
            LocalDate.of(2026, 1, 1), next, next == null ? null : "다음 프로젝트",
            next == null ? null : LocalDate.of(2026, 12, 1));
    }
}
