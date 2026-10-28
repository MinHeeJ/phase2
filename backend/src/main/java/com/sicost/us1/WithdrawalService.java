package com.sicost.us1;

import com.sicost.apiops.ProjectBoardMapper;
import com.sicost.apiops.ProjectBoardModels.OperatingParam;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.us1.WithdrawalModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class WithdrawalService {
    private final WithdrawalMapper mapper;
    private final ProjectBoardMapper parameters;
    private final Clock clock;
    public WithdrawalService(WithdrawalMapper mapper, ProjectBoardMapper parameters, Clock clock) {
        this.mapper = mapper;
        this.parameters = parameters;
        this.clock = clock;
    }
    public Snapshot snapshot(Query query) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var params = parameters.params(YearMonth.from(today).toString());
        WithdrawalRules.DispatchStandard standard;
        try {
            int months = param(params, "DISPATCH_MAX_MONTHS").num_val().intValueExact();
            List<Integer> alerts = Arrays.stream(param(params, "DISPATCH_ALERT_DAYS").txt_val().split(","))
                .map(String::trim).map(Integer::valueOf).toList();
            standard = new WithdrawalRules.DispatchStandard(months, alerts);
        } catch (ArithmeticException | NumberFormatException | NullPointerException e) {
            throw WithdrawalInput.error("month");
        }
        var all = mapper.sources().stream()
            .filter(s -> Set.of("ACTIVE", "REPLACED").contains(s.asgn_stts_cd()))
            .map(s -> WithdrawalRules.calculate(s, query.year(), today, standard)).toList();
        var summary = WithdrawalRules.summary(all, today);
        var matching = all.stream().filter(r -> WithdrawalRules.matches(r, query, today))
            .sorted(WithdrawalRules.order(query.sort())).toList();
        return new Snapshot(today, matching, summary);
    }
    private OperatingParam param(List<OperatingParam> rows, String key) {
        return rows.stream().filter(p -> key.equals(p.oprt_std_id())).findFirst()
            .orElseThrow(() -> WithdrawalInput.error("month"));
    }
}
