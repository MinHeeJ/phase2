package com.sicost.us5;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.PermissionService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.us5.MonthlyShortageModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MonthlyShortageService {
    private final MonthlyShortageMapper mapper;
    private final Clock clock;
    private final PermissionService permissions;
    public MonthlyShortageService(MonthlyShortageMapper mapper, Clock clock, PermissionService permissions) {
        this.mapper = mapper; this.clock = clock; this.permissions = permissions;
    }
    public List<Row> rows(Query query) {
        permissions.can("UI-11.read");
        var tolerance = mapper.tolerance(YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString());
        if (tolerance == null || tolerance.signum() < 0) {
            throw ApiException.badRequest("계획 대비 투입 기준값의 유효한 숫자가 없습니다. 적용월을 확인하세요.", "month");
        }
        return mapper.months(query).stream().filter(r -> r.shortageMm().compareTo(tolerance) > 0)
            .sorted(Comparator.comparing(Row::shortageMm).reversed().thenComparing(Row::plan_ym)
                .thenComparingLong(Row::prj_id).thenComparing(Row::job_cd)).toList();
    }
    public Page list(Query query) {
        var rows = rows(query);
        return new Page(rows.stream().skip((long) query.page() * query.size()).limit(query.size()).toList(),
            rows.size(), query.page(), query.size());
    }
}
