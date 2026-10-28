package com.sicost.billingsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.validation.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service("billingOperationsAmountService")
@Transactional(readOnly = true)
public class BillingAmountService {
    private final BillingMapper mapper;
    public BillingAmountService(BillingMapper mapper) { this.mapper = mapper; }

    public Preview preview(PreviewRequest request) {
        if (request.projectId() < 1) throw BillingValues.invalid("projectId");
        Project project = mapper.project(request.projectId());
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        BillingRules.requireBillable(project.contract_type());
        if ("TK".equals(project.contract_type())) {
            if (request.stageId() == null) throw BillingValues.invalid("stageId");
            Stage stage = mapper.stage(request.stageId());
            if (stage == null || stage.project_id() != project.project_id()) throw BillingValues.invalid("stageId");
            String month = stage.bill_plan_dt() == null ? null : YearMonth.from(stage.bill_plan_dt()).toString();
            return new Preview(project.project_id(), stage.stage_id(), month, stage.sply_amt(),
                BillingAmounts.suggestedVat(stage.sply_amt(), month == null ? null : mapper.vat(month)),
                stage.bill_plan_dt(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        String month = BillingValues.month(request.month(), "month").toString();
        var lines = new ArrayList<DetailLine>();
        var warnings = new ArrayList<String>();
        var missing = new ArrayList<Long>();
        for (Source source : mapper.sources(new SourceKey(project.project_id(), month))) {
            boolean free = "FREE".equals(source.kind());
            if (!free && (source.price() == null || source.price() <= 0)) {
                missing.add(source.asgn_id());
                warnings.add(source.display_name() + ": 단가 미입력");
            }
            if (attendanceMissing(source)) {
                warnings.add(source.display_name() + ": 근태 미입력");
                continue;
            }
            if (!free && source.price() == null) continue;
            BigDecimal quantity = switch (source.settlement()) {
                case "DAY" -> source.days();
                case "HOUR" -> source.hours();
                default -> source.mm_qty();
            };
            if (quantity == null) {
                warnings.add(source.display_name() + ": 투입 MM 미입력");
                continue;
            }
            long amount = free ? 0 : calculate(quantity, source.price(), 0);
            BigDecimal mm = "MONTH".equals(source.settlement()) ? source.mm_qty()
                : source.days() == null || source.denominator() == null || source.denominator().signum() <= 0
                ? null : source.days().divide(source.denominator(), 2, RoundingMode.HALF_UP);
            lines.add(new DetailLine(source.asgn_id(), source.dev_id(), source.cntrct_dev_id(),
                source.display_name(), month, mm, source.mm_qty(), source.price(), source.days(),
                source.hours(), source.overtime() == null ? BigDecimal.ZERO : source.overtime(),
                0, amount, free ? "무상" : null));
        }
        long supply = sum(lines.stream().map(DetailLine::bill_amt).toList());
        return new Preview(project.project_id(), null, month, supply,
            BillingAmounts.suggestedVat(supply, mapper.vat(month)), null, List.copyOf(lines),
            List.copyOf(warnings), List.copyOf(missing));
    }
    public static boolean attendanceMissing(Source source) {
        return "DAY".equals(source.settlement()) && source.days() == null
            || "HOUR".equals(source.settlement()) && source.hours() == null;
    }
    public static long calculate(BigDecimal quantity, long price, long overtime) {
        if (quantity == null || quantity.signum() < 0 || price < 0 || overtime < 0) {
            throw BillingValues.invalid("details");
        }
        return BillingAmounts.exact(quantity.multiply(BigDecimal.valueOf(price))
            .add(BigDecimal.valueOf(overtime)).setScale(0, RoundingMode.FLOOR), "sply_amt");
    }
    public static long sum(Collection<Long> values) {
        return BillingAmounts.exact(values.stream().map(BigDecimal::valueOf)
            .reduce(BigDecimal.ZERO, BigDecimal::add), "sply_amt");
    }
}
