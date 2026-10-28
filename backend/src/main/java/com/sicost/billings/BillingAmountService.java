package com.sicost.billings;

import com.sicost.common.validation.BillingRules;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billings.BillingModels.*;

@Service
@Transactional(readOnly = true)
public class BillingAmountService {
    private final BillingMapper mapper;
    public BillingAmountService(BillingMapper mapper) { this.mapper = mapper; }

    public Proposal calculate(Project project, Scope scope, Map<Long, Long> overtime) {
        List<Line> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if ("TK".equals(project.contract_type())) {
            var stage = mapper.stage(scope);
            if (stage == null) throw BillingRules.invalid("stage_id");
            lines.add(new Line(null, null, null, stage.name(), scope.month(), null, null,
                null, null, null, null, 0, stage.supply(), stage.name()));
        } else {
            if (!"DP".equals(project.contract_type()) && !"MONTH".equals(project.settlement())) {
                throw BillingRules.invalid("settlement");
            }
            for (var source : mapper.sources(scope)) {
                boolean free = "FREE".equals(source.kind());
                if (!free && source.price() == null) throw BillingRules.invalid("bill_untprc_amt");
                boolean monthly = "MONTH".equals(project.settlement());
                if (!monthly && source.attendance_id() == null) {
                    warnings.add(source.display_name() + ": 근태 미입력으로 상세에서 제외했습니다.");
                    continue;
                }
                long rate = free ? 0 : source.price();
                BigDecimal mm = monthly ? source.mm()
                    : source.days() == null ? null
                    : BillingRules.attendanceMm(source.days(), source.month_days());
                BigDecimal quantity = monthly ? mm
                    : "DAY".equals(project.settlement()) ? source.days() : source.hours();
                long extra = overtime.getOrDefault(source.asgn_id(), 0L);
                if ((free || !"HOUR".equals(project.settlement())) && extra != 0) {
                    throw BillingRules.invalid("ovtm_amt");
                }
                long amount = BillingRules.add(BillingRules.sourceAmount(quantity, rate), extra, "bill_amt");
                lines.add(new Line(source.asgn_id(), source.dev_id(), source.cntrct_dev_id(),
                    source.display_name(), scope.month(), mm, source.mm(), rate,
                    monthly ? null : source.days(), monthly ? null : source.hours(),
                    monthly ? null : source.overtime(), extra, amount, null));
            }
        }
        checkAssignments(lines, overtime);
        long total = sum(lines);
        return new Proposal(List.copyOf(lines), total, BillingRules.vat(total, mapper.vatRate(scope.month())), warnings);
    }
    public List<Line> manualOvertime(List<Line> lines, Map<Long, Long> amounts, Project project) {
        checkAssignments(lines, amounts);
        if (!amounts.isEmpty() && (!"DP".equals(project.contract_type()) || !"HOUR".equals(project.settlement()))) {
            if (amounts.values().stream().anyMatch(v -> v != 0)) throw BillingRules.invalid("ovtm_amt");
        }
        return lines.stream().map(line -> {
            long next = amounts.getOrDefault(line.asgn_id(), line.ovtm_amt());
            long total = BillingRules.add(line.bill_amt() - line.ovtm_amt(), next, "bill_amt");
            return new Line(line.asgn_id(), line.dev_id(), line.cntrct_dev_id(), line.display_name(),
                line.work_ym(), line.mm_qty(), line.source_mm_qty(), line.untprc_amt(),
                line.work_day_qty(), line.work_tm_qty(), line.ovtm_tm_qty(), next, total, line.rmrk_desc());
        }).toList();
    }
    private void checkAssignments(List<Line> lines, Map<Long, Long> overtime) {
        var ids = new HashSet<Long>();
        for (var line : lines) if (line.asgn_id() != null) ids.add(line.asgn_id());
        if (!ids.containsAll(overtime.keySet())) throw BillingRules.invalid("details.asgn_id");
    }
    public static long sum(List<Line> lines) {
        long sum = 0;
        for (var line : lines) sum = BillingRules.add(sum, line.bill_amt(), "sply_amt");
        return sum;
    }
}
