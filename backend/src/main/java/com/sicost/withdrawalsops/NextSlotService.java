package com.sicost.withdrawalsops;

import com.sicost.common.api.ApiException;
import com.sicost.us1.WithdrawalMapper;
import com.sicost.us1.WithdrawalModels.Source;
import com.sicost.us1.WithdrawalRules;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.withdrawalsops.NextSlotModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class NextSlotService {
    private final NextSlotMapper mapper;
    private final WithdrawalMapper withdrawals;
    private final Clock clock;
    public NextSlotService(NextSlotMapper mapper, WithdrawalMapper withdrawals, Clock clock) {
        this.mapper = mapper; this.withdrawals = withdrawals; this.clock = clock;
    }
    public NextSlotsResult get(long asgnId) {
        var profiles = mapper.profiles().stream().collect(Collectors.toMap(Profile::asgn_id, Function.identity()));
        var selected = profiles.get(asgnId);
        if (selected == null) throw ApiException.notFound("투입이 없습니다.");
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var eligible = withdrawals.sources().stream().filter(s -> eligible(s, today)).toList();
        var needs = mapper.needs();
        var grades = mapper.grades();
        Map<Long,List<NextSlot>> results = new HashMap<>();
        for (var source : eligible) {
            var profile = profiles.get(source.asgn_id());
            if (profile != null) results.put(source.asgn_id(), recommend(source, profile, needs, grades, mapper.jobs(profile.dev_id())));
        }
        var items = results.getOrDefault(asgnId, Collections.emptyList());
        long n = eligible.stream().map(Source::dev_id).distinct().count();
        long m = eligible.stream().filter(s -> !results.getOrDefault(s.asgn_id(), Collections.emptyList()).isEmpty())
            .map(Source::dev_id).distinct().count();
        var temporary = mapper.temporary(selected.dev_id()).stream().map(TemporaryRow::view).toList();
        return new NextSlotsResult(items, items.size(), n, m, temporary);
    }
    private boolean eligible(Source s, LocalDate today) {
        return WithdrawalRules.soon(s.end_dt(), today) && !"H".equals(s.ext_psblty_cd())
            && s.next_asgn_id() == null;
    }
    public List<NextSlot> recommend(Source source, Profile profile, List<Need> needs, List<String> grades, List<String> jobs) {
        YearMonth withdrawal = YearMonth.from(source.end_dt());
        YearMonth next = withdrawal.plusMonths(1);
        return needs.stream().filter(n -> n.remaining_slots() > 0)
            .filter(n -> {
                YearMonth begin = YearMonth.parse(n.bgn_ym()), end = YearMonth.parse(n.end_ym());
                return !end.isBefore(next) && !begin.isAfter(withdrawal.plusMonths(3));
            }).map(n -> score(source, profile, n, grades, jobs))
            .sorted(Comparator.comparingInt(NextSlot::mtch_scr).reversed()
                .thenComparingLong(NextSlot::waiting_days).thenComparingLong(NextSlot::need_id))
            .limit(5).toList();
    }
    private NextSlot score(Source source, Profile profile, Need need, List<String> grades, List<String> jobs) {
        long gap = ChronoUnit.MONTHS.between(YearMonth.from(source.end_dt()), YearMonth.parse(need.bgn_ym())) - 1;
        int gapScore = gap <= 0 ? 25 : gap == 1 ? 15 : gap == 2 ? 8 : gap == 3 ? 3 : 0;
        int grade = 0;
        if (profile.grd_cd() != null && need.grd_cd() != null) {
            if (profile.grd_cd().equals(need.grd_cd())) grade = 20;
            else if (grades.contains(profile.grd_cd()) && grades.contains(need.grd_cd())
                    && Math.abs(grades.indexOf(profile.grd_cd()) - grades.indexOf(need.grd_cd())) == 1) grade = 10;
        }
        var components = new ScoreComponents(jobs.contains(need.job_cd()) ? 40 : 0, grade, gapScore,
            profile.cust_ptnr_id() != null && profile.cust_ptnr_id().equals(need.cust_ptnr_id()) ? 10 : 0,
            need.win_prblt_rt().compareTo(new BigDecimal("60")) >= 0 ? 5 : 0);
        long waiting = ChronoUnit.DAYS.between(source.end_dt(), YearMonth.parse(need.bgn_ym()).atDay(1)) - 1;
        return new NextSlot(need.need_id(), need.oppt_id(), need.oppt_nm(), need.job_cd(), need.grd_cd(),
            need.bgn_ym(), need.end_ym(), need.remaining_slots(), components.total(), components,
            Math.max(0, waiting), need.win_prblt_rt(), waiting < 0 ? List.of("OVERLAP: 철수 전 시작하는 자리입니다.") : List.of());
    }
}
