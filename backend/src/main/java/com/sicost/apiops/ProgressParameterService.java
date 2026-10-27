package com.sicost.apiops;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.PermissionService;
import com.sicost.common.security.ProjectActions;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.apiops.ProjectBoardModels.*;

@Service
@Transactional(readOnly = true)
public class ProgressParameterService {
    private static final List<String> KEYS = List.of("SIG_DELAY_WARN", "SIG_DELAY_RISK", "SIG_COST_WARN",
        "SIG_COST_RISK", "SIG_STALE_DAYS", "BUDGET_RATE");
    private final ProjectBoardMapper mapper;
    private final Clock clock;
    private final PermissionService permissions;
    public ProgressParameterService(ProjectBoardMapper mapper, Clock clock, PermissionService permissions) {
        this.mapper = mapper; this.clock = clock; this.permissions = permissions;
    }
    public List<OperatingParam> get(String month) {
        permissions.can(ProjectActions.key("getParams"));
        if (month == null) month = YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
        if (!month.matches("[0-9]{4}-(0[1-9]|1[0-2])")) {
            throw ApiException.badRequest("연월을 확인하세요.", "month");
        }
        var rows = mapper.params(month);
        for (String key : KEYS) {
            var row = rows.stream().filter(r -> key.equals(r.oprt_std_id())).findFirst();
            try {
                if (row.isEmpty()) throw new NumberFormatException();
                row.get().numeric();
            } catch (NumberFormatException | NullPointerException ex) {
                throw ApiException.badRequest("진행 현황 기준값 " + key + "의 유효한 숫자가 없습니다. 적용월을 확인하세요.", "month");
            }
        }
        return rows;
    }
}
